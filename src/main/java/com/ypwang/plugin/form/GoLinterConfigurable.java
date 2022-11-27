package com.ypwang.plugin.form;

import com.goide.configuration.GoSdkConfigurable;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogBuilder;
import com.intellij.openapi.ui.JBPopupMenu;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
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
import com.ypwang.plugin.GoLinterSettings;
import com.ypwang.plugin.UtilitiesKt;
import com.ypwang.plugin.model.GoLinter;
import com.ypwang.plugin.platform.Platform;
import com.ypwang.plugin.platform.Windows;
import kotlin.Unit;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.naming.NoPermissionException;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.ypwang.plugin.ConstantsKt.Const_Path;

public class GoLinterConfigurable implements SearchableConfigurable, Disposable {
    private GoLinterSettings settings;
    private static final String CONFIG_HELP = "https://golangci-lint.run/usage/configuration/#config-file";

    private static final Set<String> suggestLinters =
            Set.of(
                    "gosimple", "govet", "ineffassign", "staticcheck", "bodyclose",
                    "dupl", "exportloopref", "funlen", "gocognit", "goconst", "revive",
                    "gocritic", "gocyclo", "goprintffuncname", "gosec", "interfacer",
                    "maligned", "prealloc", "stylecheck", "whitespace", "errorlint"
            );
    private static long lastSavedTime = Long.MIN_VALUE;

    public static long getLastSavedTime() {
        return lastSavedTime;
    }

    private JPanel settingPanel;
    private JComboBox<String> linterChooseComboBox;
    private JButton linterChooseButton;
    private JButton fetchLatestReleaseButton;
    private JLabel projectDir;
    private JButton customProjectSelectButton;
    private LinkLabel<String> configLabel1;
    private JButton suggestButton;
    private LinkLabel<String> configLabel2;
    private JButton customConfig;
    private JPopupMenu customConfigPopup;
    private JLabel helpDocumentLabel;
    private JPanel linterSelectPanel;
    private JCheckBox projectRootCheckBox;
    private JComboBox<Integer> concurrencyComboBox;
    private AsyncProcessIcon.Big refreshProcessIcon;
    private JTable linterTable;

    private LabelTableHeaderRender linterTableHeader;
    @NotNull
    private final Project curProject;
    @NotNull
    private final Platform platform;
    @NotNull
    private final List<GoLinter> allLinters = new ArrayList<>();
    @NotNull
    private final Set<String> enabledLinters = new HashSet<>();

    private boolean modified = false;

    public GoLinterConfigurable(@NotNull Project project) {
        curProject = project;
        platform = Platform.Companion.platformFactory(project);
        settings = GoLinterSettings.getInstance(project);

        linterChooseComboBox.setRenderer(new FileExistCellRender());
        linterChooseComboBox.addActionListener(this::linterSelected);
        concurrencyComboBox.addActionListener(l -> modified = true);
        linterChooseButton.addActionListener(e -> linterChoose());
        projectRootCheckBox.addItemListener(this::enableProjectRoot);
        customProjectSelectButton.addActionListener(e -> customProjectDir());
    }

    @SuppressWarnings("unchecked")
    private void createUIComponents() {
        // initialize components
        helpDocumentLabel = createLinkLabel(null, desktop -> desktop.browse(new URL(CONFIG_HELP).toURI()));

        fetchLatestReleaseButton = new JButton();
        fetchLatestReleaseButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                JPopupMenu fetchLatestReleasePopup = new JBPopupMenu();
                fetchLatestReleasePopup.add(new JMenuItem(new AbstractAction(String.format("Download to %s", platform.defaultPath())) {
                    public void actionPerformed(ActionEvent e) {
                        fetchLatestExecutable(platform.defaultPath());
                    }
                }));
                fetchLatestReleasePopup.add(new JMenuItem(new AbstractAction("Select folder...") {
                    public void actionPerformed(ActionEvent e) {
                        FileChooserDescriptor fileChooserDescriptor = new FileChooserDescriptor(false, true, false, false, false, false);
                        VirtualFile hint = LocalFileSystem.getInstance().findFileByPath(platform.defaultPath());
                        VirtualFile folder = FileChooser.chooseFile(fileChooserDescriptor, GoLinterConfigurable.this.settingPanel, null, hint);
                        if (folder != null) {
                            fetchLatestExecutable(Paths.get(folder.getPath()).toString());
                        }
                    }
                }));
                fetchLatestReleasePopup.show(e.getComponent(), e.getX(), e.getY());
            }
        });

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
                    linter.getFullDescription()
            });
        }
    }

    // if `selected` not in combo list, add it into combo list
    // set combo box to `selected`
    private void setLinterExecutables(@NotNull String selected) {
        ProgressManager.getInstance().run(new Task.Backgroundable(curProject, "Reset") {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                List<String> paths = new ArrayList<>(platform.getPathList());
                paths.add(platform.defaultPath());
                // get golangci-lint executables in PATH
                Vector<String> items = paths.stream()
                        .map(path -> Paths.get(path, platform.linterName()).toString())
                        .filter(platform::canExecute)
                        .distinct()
                        .collect(Collectors.toCollection(Vector::new));

                if (!items.contains(selected)) {
                    items.add(selected);
                }

                ApplicationManager.getApplication().invokeLater(() -> {
                    DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(items);
                    linterChooseComboBox.setModel(model);
                    linterChooseComboBox.setSelectedItem(selected);
                }, ModalityState.stateForComponent(linterChooseComboBox));
            }
        });
    }

    private void resetPanel() {
        Optional<String> configFile = UtilitiesKt.findCustomConfigInPath(projectDir.getText()).map(path -> Paths.get(path).toString());

        Arrays.asList(configLabel1, suggestButton, configLabel2, customConfigPopup, customConfig).forEach(component -> {
            if (component != null) {
                settingPanel.remove(component);
                UIUtil.dispose(component);
            }
        });

        configFile.ifPresentOrElse(
                f -> {
                    // found a valid config file
                    configLabel1 = createLinkLabel(String.format("Using %s", f), desktop -> desktop.open(new File(f)));
                    GridConstraints constraints = new GridConstraints(3, 0, 1, 3, 0, 1, 3, 0, null, null, null, 0, false);
                    settingPanel.add(configLabel1, constraints);
                    linterTable.setEnabled(false);
                },
                () -> {
                    customConfigPopup = new JBPopupMenu();
                    customConfigPopup.add(new JMenuItem(new AbstractAction("None") {
                        public void actionPerformed(ActionEvent e) {
                            configLabel2.setText("");
                            configLabel2.setListener(null, null);
                            suggestButton.setEnabled(true);
                            linterTable.setEnabled(true);
                            modified = true;
                            initializeLinters();
                        }
                    }));
                    customConfigPopup.add(new JMenuItem(new AbstractAction("Select file...") {
                        public void actionPerformed(ActionEvent e) {
                            FileChooserDescriptor fileChooserDescriptor = new FileChooserDescriptor(true, false, false, false, false, false);

                            VirtualFile curSelected = null;
                            if (configLabel2.getText() != null) {
                                curSelected = LocalFileSystem.getInstance().findFileByPath(configLabel2.getText());
                            }

                            VirtualFile folder = FileChooser.chooseFile(fileChooserDescriptor, GoLinterConfigurable.this.settingPanel, null, curSelected);
                            if (folder != null) {
                                String config = Paths.get(folder.getPath()).toString();

                                suggestButton.setEnabled(false);
                                linterTable.setEnabled(false);
                                configLabel2.setText(config);
                                configLabel2.setListener((aSource, aLinkData) -> {
                                    try {
                                        Desktop.getDesktop().open(new File(config));
                                    } catch (Exception ex) {
                                        // ignore
                                    }
                                }, null);
                                modified = true;
                                initializeLinters();
                            }
                        }
                    }));

                    customConfig = new JButton("Using config:");
                    customConfig.addMouseListener(new MouseAdapter() {
                        @Override
                        public void mousePressed(MouseEvent e) {
                            customConfigPopup.show(e.getComponent(), e.getX(), e.getY());
                        }
                    });

                    GridConstraints constraints = new GridConstraints(3, 0, 1, 1, 0, 1, 3, 0, null, null, null, 0, false);
                    settingPanel.add(customConfig, constraints);

                    constraints.setColumn(1);
                    configLabel2 = new LinkLabel<>();
                    settingPanel.add(configLabel2, constraints);

                    constraints.setColumn(2);
                    suggestButton = new JButton("Suggest me!");
                    suggestButton.addActionListener(this::suggestLinters);
                    settingPanel.add(suggestButton, constraints);

                    String config = settings.getCustomConfigFile();
                    if (config != null) {
                        suggestButton.setEnabled(false);
                        linterTable.setEnabled(false);
                        configLabel2.setText(config);
                        configLabel2.setListener((aSource, aLinkData) -> {
                            try {
                                Desktop.getDesktop().open(new File(config));
                            } catch (Exception e) {
                                // ignore
                            }
                        }, null);
                    } else {
                        configLabel2.setText("");
                        configLabel2.setListener(null, null);
                        suggestButton.setEnabled(true);
                        linterTable.setEnabled(true);
                    }
                }
        );
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
                    List<String> arguments = new ArrayList<>();
                    arguments.add(platform.toRunningOSPath(selectedLinter));
                    arguments.add("linters");

                    if (configLabel2 != null && !configLabel2.getText().isEmpty()) {
                        arguments.add("-c");
                        arguments.add(platform.toRunningOSPath(configLabel2.getText()));
                    }

                    allLinters.addAll(UtilitiesKt.parseLinters(
                            curProject,
                            platform.runProcess(
                                    arguments,
                                    StringUtils.isNotEmpty(projectDir.getText()) ? platform.toRunningOSPath(projectDir.getText()) : null,
                                    Collections.singletonList(Const_Path),
                                    Charset.defaultCharset()
                            )
                    ));
                } catch (Exception e) {
                    UtilitiesKt.getLogger().error(e);
                    ApplicationManager.getApplication().invokeLater(
                            () -> {
                                showErrorBox("Failed to Discover Linters:", e.getMessage());
                                ShowSettingsUtil.getInstance().editConfigurable(curProject, new GoSdkConfigurable(curProject, true));
                            },
                            ModalityState.stateForComponent(settingPanel)
                    );
                }

                if (settings.getLinterSelected() && linterTable.isEnabled()) {
                    // previously selected linters
                    enabledLinters.addAll(settings.getEnabledLinters());
                } else {
                    // default enabled linters
                    enabledLinters.addAll(allLinters.stream().filter(GoLinter::getDefaultEnabled).map(GoLinter::getName).collect(Collectors.toSet()));
                }
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

        void acceptThrows(T elem) throws IOException, URISyntaxException;
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
        settings.setConcurrency((Integer) concurrencyComboBox.getSelectedItem());

        if (linterChooseComboBox.getSelectedItem() != null) {
            settings.setGoLinterExe((String) linterChooseComboBox.getSelectedItem());
        }

        settings.setEnableCustomProjectDir(projectRootCheckBox.isSelected());
        String customProjectDir = null;
        if (curProject.getBasePath() == null || !Paths.get(curProject.getBasePath()).toString().equals(projectDir.getText()))
            customProjectDir = projectDir.getText();

        settings.setCustomProjectDir(customProjectDir);

        if (configLabel2 != null) {
            String customConfigPath = null;
            if (!configLabel2.getText().isEmpty())
                customConfigPath = configLabel2.getText();
            settings.setCustomConfigFile(customConfigPath);
        }

        if (enabledLinters == allLinters.stream().filter(GoLinter::getDefaultEnabled).map(GoLinter::getName).collect(Collectors.toSet())) {
            settings.setLinterSelected(false);
        } else {
            settings.setLinterSelected(true);
            settings.setEnabledLinters(new ArrayList<>(enabledLinters));
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
        {
            DefaultComboBoxModel<Integer> model = new DefaultComboBoxModel<>();
            model.addAll(IntStream.rangeClosed(1, Runtime.getRuntime().availableProcessors()).boxed().toList());
            concurrencyComboBox.setModel(model);
            concurrencyComboBox.setSelectedItem(settings.getConcurrency());
        }

        String dir = settings.getCustomProjectDir();
        if (dir == null)
            dir = curProject.getBasePath();
        if (dir == null)
            dir = "";
        projectRootCheckBox.setSelected(settings.getEnableCustomProjectDir());
        projectDir.setText(Paths.get(dir).toString());

        resetPanel();

        final String savedLinter = settings.getGoLinterExe();
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
        UIUtil.dispose(this.projectRootCheckBox);
        UIUtil.dispose(this.projectDir);
        UIUtil.dispose(this.customProjectSelectButton);
        UIUtil.dispose(this.configLabel1);
        UIUtil.dispose(this.suggestButton);
        UIUtil.dispose(this.configLabel2);
        UIUtil.dispose(this.customConfig);
        UIUtil.dispose(this.customConfigPopup);
        UIUtil.dispose(this.helpDocumentLabel);
        UIUtil.dispose(this.linterSelectPanel);
        UIUtil.dispose(this.refreshProcessIcon);
        UIUtil.dispose(this.linterTable);
        this.fetchLatestReleaseButton = null;
        this.configLabel1 = null;
        this.suggestButton = null;
        this.configLabel2 = null;
        this.customConfig = null;
        this.customConfigPopup = null;
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

        Condition<VirtualFile> filter;
        if (platform instanceof Windows)
            filter = file -> "exe".equalsIgnoreCase(file.getExtension());
        else
            filter = file -> platform.canExecute(file.getPath());
        fileChooserDescriptor.withFileFilter(filter);

        String hintPath = (String) linterChooseComboBox.getSelectedItem();
        if (hintPath == null || hintPath.isEmpty()) {
            hintPath = platform.defaultPath();
        }

        VirtualFile file = FileChooser.chooseFile(fileChooserDescriptor, GoLinterConfigurable.this.settingPanel, null,
                LocalFileSystem.getInstance().findFileByPath(hintPath));

        if (file != null) {
            // because `file.getPath()` returns linux path, seems weird on windows
            String systemPath = Paths.get(file.getPath()).toString();
            if (!systemPath.equals(hintPath))
                modified = true;

            setLinterExecutables(systemPath);
        }
    }

    private void fetchLatestExecutable(String folder) {
        try {
            if (!platform.canWrite(folder)) {
                throw new NoPermissionException(String.format("cannot write to %s", folder));
            }

            setLinterExecutables(ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
                ProgressIndicator progressIndicator = ProgressManager.getInstance().getProgressIndicator();
                progressIndicator.setIndeterminate(false);
                return platform.fetchLatestGoLinter(
                        folder,
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

    private void enableProjectRoot(ItemEvent e) {
        boolean enabled = projectRootCheckBox.isSelected();
        projectDir.setEnabled(enabled);
        customProjectSelectButton.setEnabled(enabled);
        modified = true;
    }

    private void customProjectDir() {
        FileChooserDescriptor fileChooserDescriptor = new FileChooserDescriptor(false, true, false, false, false, false);

        VirtualFile hint = LocalFileSystem.getInstance().findFileByPath(projectDir.getText());
        VirtualFile folder = FileChooser.chooseFile(fileChooserDescriptor, GoLinterConfigurable.this.settingPanel, null, hint);
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
