package com.ypwang.plugin.form;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogBuilder;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.util.ui.AsyncProcessIcon;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.ypwang.plugin.GoLinterConfig;
import com.ypwang.plugin.GolangCiOutputParser;
import com.ypwang.plugin.UtilitiesKt;
import com.ypwang.plugin.model.GoLinter;
import kotlin.Unit;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class GoLinterSettings implements SearchableConfigurable, Disposable {
    private static final String PROJECT_ROOT_HELP = "https://github.com/xxpxxxxp/intellij-plugin-golangci-lint/blob/master/README.md#go-project-as-sub-folder";
    private static final String CONFIG_HELP = "https://golangci-lint.run/usage/configuration/#config-file";

    private static final Set<String> suggestLinters =
            Set.of(
                    "gosimple", "govet", "ineffassign", "staticcheck", "bodyclose",
                    "dupl", "exportloopref", "funlen", "gocognit", "goconst", "golint",
                    "gocritic", "gocyclo", "goprintffuncname", "gosec", "interfacer",
                    "maligned", "prealloc", "stylecheck", "unconvert", "whitespace", "errorlint"
            );
    private static long lastSavedTime = Long.MIN_VALUE;

    public static long getLastSavedTime() {
        return lastSavedTime;
    }

    private JPanel settingPanel;
    private JComboBox<String> linterChooseComboBox;
    private JButton linterChooseButton;
    private JButton fetchLatestReleaseButton;
    private JLabel projectRootLabel;
    private JLabel projectDir;
    private JButton customProjectSelectButton;
    private JComponent multiLabel;
    private JLabel helpDocumentLabel;
    private JPanel linterSelectPanel;
    private AsyncProcessIcon.Big refreshProcessIcon;
    private JTable linterTable;

    private LabelTableHeaderRender linterTableHeader;
    @NotNull
    private final Project curProject;
    @NotNull
    private final List<GoLinter> allLinters = new ArrayList<>();
    @NotNull
    private final Set<String> enabledLinters = new HashSet<>();
    @NotNull
    private final Set<String> lintersInPath = getLinterFromPath();     // immutable

    private boolean modified = false;

    public GoLinterSettings(@NotNull Project project) {
        curProject = project;

        linterChooseComboBox.setRenderer(new FileExistCellRender());
        linterChooseComboBox.addActionListener(this::linterSelected);
        linterChooseButton.addActionListener(e -> linterChoose());
        fetchLatestReleaseButton.addActionListener(e -> goGet());
        customProjectSelectButton.addActionListener(e -> customProjectDir());
    }

    @SuppressWarnings("unchecked")
    private void createUIComponents() {
        // initialize components
        projectRootLabel = createLinkLabel(null, desktop -> desktop.browse(new URL(PROJECT_ROOT_HELP).toURI()));
        helpDocumentLabel = createLinkLabel(null, desktop -> desktop.browse(new URL(CONFIG_HELP).toURI()));
        linterSelectPanel = new JPanel(new CardLayout());

        // linterTable initialization
        linterTable = new JBTable() {
            @Override
            public void setEnabled(boolean enabled) {
                super.setEnabled(enabled);
                if (linterTableHeader != null) {
                    linterTableHeader.setEnabled(enabled);
                }
            }
        };

        linterTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        linterTable.getTableHeader().setReorderingAllowed(false);

        DefaultTableModel model = new DefaultTableModel(new String[]{"", "Description"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0;
            }
        };
        linterTable.setModel(model);

        linterTable.getColumnModel().getColumn(1).setPreferredWidth(400);
        // customize first column header & render & editor
        TableColumn tc = linterTable.getColumnModel().getColumn(0);

        CheckBoxCellEditor editor = new CheckBoxCellEditor(e -> {
            JCheckBox checkBox = (JCheckBox) e.getSource();
            if (checkBox.isSelected()) {
                enabledLinters.add(checkBox.getText());
            } else {
                enabledLinters.remove(checkBox.getText());
            }
            linterTableHeader.switchState(enabledLinters.size() != allLinters.size());
            modified = true;
        });
        tc.setCellRenderer(editor);
        tc.setCellEditor(editor);
        tc.setPreferredWidth(120);

        linterTableHeader = new LabelTableHeaderRender(linterTable, isEnableAllStatus -> {
            enabledLinters.clear();
            if (isEnableAllStatus) {
                // enable all clicked
                enabledLinters.addAll(allLinters.stream().map(GoLinter::getName).collect(Collectors.toSet()));
            }

            if (linterTable.isEditing()) {
                linterTable.getCellEditor().cancelCellEditing();
            }

            // reset linters
            for (int r = 0; r < linterTable.getRowCount(); r++) {
                linterTable.setValueAt(Pair.create(
                        isEnableAllStatus,
                        ((Pair<Boolean, String>) linterTable.getValueAt(r, 0)).getSecond()
                ), r, 0);
            }

            modified = true;
        });
        tc.setHeaderRenderer(linterTableHeader);

        refreshProcessIcon = new AsyncProcessIcon.Big("progress");
        linterSelectPanel.add("lintersTable", new JBScrollPane(linterTable));
        linterSelectPanel.add("refreshProcessIcon", refreshProcessIcon);
    }

    // refresh lintersTable with selected item of linterComboBox
    private void refreshTableContent() {
        DefaultTableModel model = (DefaultTableModel) linterTable.getModel();
        model.setRowCount(0);

        for (GoLinter linter : allLinters) {
            model.addRow(new Object[]{
                    new Pair<>(enabledLinters.contains(linter.getName()), linter.getName()),
                    linter.getDescription()
            });
        }
    }

    // if `selected` not in combo list, add it into combo list
    // set combo box to `selected`
    private void setLinterExecutables(@NotNull String selected) {
        Vector<String> items = new Vector<>(lintersInPath);

        if (!lintersInPath.contains(selected)) {
            items.add(selected);
        }

        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(items);
        linterChooseComboBox.setModel(model);
        linterChooseComboBox.setSelectedItem(selected);
    }

    // return golangci-lint executables in PATH
    private Set<String> getLinterFromPath() {
        String pathStr = System.getenv("PATH");
        if (pathStr != null) {
            Set<String> paths = new HashSet<>(Arrays.asList(pathStr.split(File.pathSeparator)));
            // special case: downloaded previously by this plugin
            paths.add(UtilitiesKt.getExecutionDir());

            return paths.stream()
                    .map(path -> Paths.get(path, UtilitiesKt.getLinterExecutableName()))
                    .filter(fullPath -> fullPath.toFile().canExecute())
                    .map(Path::toString)
                    .collect(Collectors.toSet());
        }

        return Collections.emptySet();
    }

    private void resetPanel() {
        Optional<String> configFile = UtilitiesKt.findCustomConfigInPath(projectDir.getText()).map(path -> Paths.get(path).toString());
        GridConstraints constraints = new GridConstraints(2, 0, 1, 3, 0, 1, 3, 0, null, null, null, 0, false);

        if (multiLabel != null) {
            settingPanel.remove(multiLabel);
            UIUtil.dispose(multiLabel);
        }

        configFile.ifPresentOrElse(
                f -> {
                    // found an valid config file
                    multiLabel = createLinkLabel(String.format("Using %s", f), desktop -> desktop.open(new File(f)));
                    linterTable.setEnabled(false);
                },
                () -> {
                    JButton tmp = new JButton("Suggest me!");
                    tmp.addActionListener(this::suggestLinters);

                    constraints.setColSpan(1);
                    multiLabel = tmp;
                    linterTable.setEnabled(true);
                }
        );

        settingPanel.add(multiLabel, constraints);
    }

    private void initializeLinters() {
        final String selectedLinter = (String) linterChooseComboBox.getSelectedItem();

        // refresh linters
        CardLayout cl = (CardLayout) linterSelectPanel.getLayout();
        refreshProcessIcon.resume();
        cl.show(linterSelectPanel, "refreshProcessIcon");

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            allLinters.clear();
            enabledLinters.clear();

            if (selectedLinter != null && new File(selectedLinter).canExecute()) {
                try {
                    allLinters.addAll(GolangCiOutputParser.INSTANCE.parseLinters(GolangCiOutputParser.INSTANCE.runProcess(
                            Arrays.asList(selectedLinter, "linters"),
                            StringUtils.isNotEmpty(projectDir.getText()) ? projectDir.getText() : null,
                            Collections.singletonMap("PATH", UtilitiesKt.getSystemPath(curProject))
                    )));
                } catch (Exception e) {
                    UtilitiesKt.getLogger().error(e);
                    ApplicationManager.getApplication().invokeLater(
                            () -> showErrorBox("Failed to Discover Linters", "Invalid 'GOROOT' in IDE"),
                            ModalityState.any()
                    );
                }

                String[] enabledLintersInConfig = GoLinterConfig.INSTANCE.getEnabledLinters();
                if (enabledLintersInConfig != null && linterTable.isEnabled()) {
                    // previously selected linters
                    enabledLinters.addAll(Arrays.stream(enabledLintersInConfig).collect(Collectors.toSet()));
                } else {
                    // default enabled linters
                    enabledLinters.addAll(allLinters.stream().filter(GoLinter::getDefaultEnabled).map(GoLinter::getName).collect(Collectors.toSet()));
                }

                multiLabel.setEnabled(true);
            }

            ApplicationManager.getApplication().invokeLater(
                    () -> {
                        linterTableHeader.switchState(enabledLinters.size() != allLinters.size());

                        cl.show(linterSelectPanel, "lintersTable");
                        refreshProcessIcon.suspend();

                        refreshTableContent();
                    },
                    ModalityState.any()
            );
        });
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> extends Consumer<T> {
        @Override
        default void accept(final T elem) {
            try {
                acceptThrows(elem);
            } catch (final Exception e) {
                // ignore
            }
        }

        void acceptThrows(T elem) throws Exception;
    }

    private LinkLabel<String> createLinkLabel(@Nullable String text, ThrowingConsumer<Desktop> onClick) {
        return new LinkLabel<>(text, null, (aSource, aLinkData) -> onClick.accept(Desktop.getDesktop()));
    }

    // ----------------------------- Override Configurable -----------------------------
    @NotNull
    @Override
    public String getId() {
        return "preference.GoLinterConfigurable";
    }

    @Override
    public String getDisplayName() {
        return "Go Linter";
    }

    @Nullable
    @Override
    public String getHelpTopic() {
        return "preference.GoLinterConfigurable";
    }

    @Override
    public void apply() {
        if (linterChooseComboBox.getSelectedItem() != null) {
            GoLinterConfig.INSTANCE.setGoLinterExe((String) linterChooseComboBox.getSelectedItem());
        }

        if (curProject.getBasePath() == null || !Paths.get(curProject.getBasePath()).toString().equals(projectDir.getText())) {
            GoLinterConfig.INSTANCE.setCustomProjectDir(Optional.of(projectDir.getText()));
        }

        if (!enabledLinters.isEmpty()) {
            GoLinterConfig.INSTANCE.setEnabledLinters(enabledLinters.toArray(new String[0]));
        }

        lastSavedTime = System.currentTimeMillis();
        modified = false;
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        return settingPanel;
    }

    @Override
    public boolean isModified() {
        return modified;
    }

    @Override
    public void reset() {
        String dir = GoLinterConfig.INSTANCE.getCustomProjectDir().orElse(curProject.getBasePath());
        if (dir == null) {
            dir = "";
        }
        projectDir.setText(Paths.get(dir).toString());

        resetPanel();

        final String savedLinter = GoLinterConfig.INSTANCE.getGoLinterExe();
        if (!savedLinter.equals(linterChooseComboBox.getSelectedItem())) {
            setLinterExecutables(savedLinter);
        } else {
            initializeLinters();
        }

        modified = false;
    }

    @Override
    public void disposeUIResources() {
        Disposer.dispose(this);
    }

    @Override
    public void dispose() {
        UIUtil.dispose(this.settingPanel);
        UIUtil.dispose(this.linterChooseComboBox);
        UIUtil.dispose(this.linterChooseButton);
        UIUtil.dispose(this.fetchLatestReleaseButton);
        UIUtil.dispose(this.projectRootLabel);
        UIUtil.dispose(this.projectDir);
        UIUtil.dispose(this.customProjectSelectButton);
        UIUtil.dispose(this.multiLabel);
        UIUtil.dispose(this.helpDocumentLabel);
        UIUtil.dispose(this.linterSelectPanel);
        UIUtil.dispose(this.refreshProcessIcon);
        UIUtil.dispose(this.linterTable);
        this.projectRootLabel = null;
        this.multiLabel = null;
        this.helpDocumentLabel = null;
        this.linterSelectPanel = null;
        this.refreshProcessIcon = null;
        this.linterTable = null;
    }

    //----------------------------- ActionListeners -----------------------------
    private void linterSelected(ActionEvent e) {
        // selection changed
        modified = true;
        initializeLinters();
    }

    private void linterChoose() {
        FileChooserDescriptor fileChooserDescriptor = new FileChooserDescriptor(true, false, false, false, false, false);

        if (SystemInfo.isWindows) {
            fileChooserDescriptor.withFileFilter((VirtualFile file) -> "exe".equalsIgnoreCase(file.getExtension()));
        }

        VirtualFile curSelected = null;
        if (linterChooseComboBox.getSelectedItem() != null) {
            curSelected = LocalFileSystem.getInstance().findFileByPath((String) linterChooseComboBox.getSelectedItem());
        }

        VirtualFile file = FileChooser.chooseFile(fileChooserDescriptor, GoLinterSettings.this.settingPanel, null, curSelected);

        if (file != null) {
            // because `file.getPath()` returns linux path, seems weird on windows
            String systemPath = Paths.get(file.getPath()).toString();
            if (curSelected == null || !systemPath.equals(curSelected.getPath()))
                modified = true;

            setLinterExecutables(systemPath);
        }
    }

    private void goGet() {
        try {
            setLinterExecutables(ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
                ProgressIndicator progressIndicator = ProgressManager.getInstance().getProgressIndicator();
                return UtilitiesKt.fetchLatestGoLinter(
                        (String s) -> {
                            progressIndicator.setText(s);
                            return Unit.INSTANCE;
                        },
                        (Double f) -> {
                            progressIndicator.setFraction(f);
                            return Unit.INSTANCE;
                        },
                        progressIndicator::isCanceled);
            }, "Get latest release", true, curProject));
            modified = true;
        } catch (ProcessCanceledException ex) {
            UtilitiesKt.getLogger().info("get latest golangci-lint cancelled");
        } catch (Exception ex) {
            showErrorBox("Failed to Get Latest Release", ex.getMessage());
        }
    }

    private void customProjectDir() {
        FileChooserDescriptor fileChooserDescriptor = new FileChooserDescriptor(false, true, false, false, false, false);

        VirtualFile hint = LocalFileSystem.getInstance().findFileByPath(projectDir.getText());
        VirtualFile folder = FileChooser.chooseFile(fileChooserDescriptor, GoLinterSettings.this.settingPanel, null, hint);
        if (folder != null) {
            // because `file.getPath()` returns linux path, seems weird on windows
            String selected = Paths.get(folder.getPath()).toString();
            if (!projectDir.getText().equals(selected)) {
                modified = true;
                projectDir.setText(selected);
                resetPanel();
                initializeLinters();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void suggestLinters(ActionEvent actionEvent) {
        if (linterTable.getRowCount() > 0) {
            enabledLinters.clear();
            enabledLinters.addAll(suggestLinters);
            linterTableHeader.switchState(enabledLinters.size() != allLinters.size());
            for (int r = 0; r < linterTable.getRowCount(); r++) {
                String linter = ((Pair<Boolean, String>) linterTable.getValueAt(r, 0)).getSecond();
                linterTable.setValueAt(Pair.create(suggestLinters.contains(linter), linter), r, 0);
            }

            modified = true;
        }
    }

    private void showErrorBox(String title, String message) {
        JLabel messageLabel = new JBLabel(
                message,
                new ImageIcon(new ImageIcon(this.getClass().getResource("/images/mole.png")).getImage().getScaledInstance(60, 60, Image.SCALE_SMOOTH)),
                SwingConstants.CENTER);
        messageLabel.setBorder(JBUI.Borders.empty(10));
        messageLabel.setIconTextGap(20);
        DialogBuilder builder = new DialogBuilder(settingPanel).title(title).centerPanel(messageLabel).resizable(false);
        builder.removeAllActions();
        builder.addOkAction();
        builder.show();
        builder.dispose();
        UIUtil.dispose(messageLabel);
    }
}
