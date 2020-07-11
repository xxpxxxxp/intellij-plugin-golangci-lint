package com.ypwang.plugin.form;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.ComponentValidator;
import com.intellij.openapi.ui.DialogBuilder;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.AsyncProcessIcon;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.ypwang.plugin.GoLinterConfig;
import com.ypwang.plugin.GoLinterLocalInspection;
import com.ypwang.plugin.GolangCiOutputParser;
import com.ypwang.plugin.UtilitiesKt;
import com.ypwang.plugin.model.GoLinter;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
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
import java.util.stream.Collectors;

public class GoLinterSettings implements SearchableConfigurable, Disposable {
    private static long lastSavedTime = Long.MIN_VALUE;
    public static long getLastSavedTime() {
        return lastSavedTime;
    }

    private JPanel settingPanel;
    private JComboBox<String> linterChooseComboBox;
    private JButton linterChooseButton;
    private JButton fetchLatestReleaseButton;
    private JCheckBox useCustomOptionsCheckBox;
    private HintTextField customOptionsField;
    private JComponent configFileHintLabel;
    private JPanel linterSelectPanel;
    private JLabel helpDocumentLabel;
    private AsyncProcessIcon.Big refreshProcessIcon;
    private JTable linterTable;

    private LabelTableHeaderRender linterTableHeader;
    private List<GoLinter> allLinters;
    private Set<String> enabledLinters;

    private boolean modified = false;
    private HashSet<String> lintersInPath;
    private final Project curProject;
    private String linterPathSelected;
    private String configFile = "";     // not suppose to change in current dialog

    public GoLinterSettings(@NotNull Project project) {
        curProject = project;

        linterChooseComboBox.addActionListener(this::linterSelected);
        linterChooseButton.addActionListener(e -> linterChoose());
        fetchLatestReleaseButton.addActionListener(e -> goGet());
        useCustomOptionsCheckBox.addActionListener(e -> customOptionsField.setEnabled(buttonActionPerformed(e)));
    }

    @SuppressWarnings("unchecked")
    private void createUIComponents() {
        // initialize components
        linterChooseComboBox = new ComboBox<>();
        fetchLatestReleaseButton = new JButton();
        useCustomOptionsCheckBox = new JCheckBox();
        customOptionsField = new HintTextField("Please be careful with parameters supplied...");
        helpDocumentLabel = new LinkLabel<String>(null, null, (aSource, aLinkData) -> {
            try {
                Desktop.getDesktop().browse(new URL("https://golangci-lint.run/usage/configuration/#config-file").toURI());
            } catch (Exception e) {
                // ignore
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
            if (isEnableAllStatus) {
                // enable all clicked
                if (allLinters != null) {
                    enabledLinters = allLinters.stream().map(GoLinter::getName).collect(Collectors.toSet());
                }
            } else {
                // disable all clicked
                if (enabledLinters != null) {
                    enabledLinters.clear();
                }
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

        linterChooseComboBox.setRenderer(new FileExistCellRender());

        // Components initialization
        new ComponentValidator(curProject).withValidator(() -> {
            String text = customOptionsField.getText();
            if (text.contains("-E") || text.contains("-D")) {
                modified = false;
                return new ValidationInfo("Please enable/disable linters in table below", customOptionsField);
            } else if (text.contains("-v") || text.contains("--out-format")) {
                modified = false;
                return new ValidationInfo("'--verbose'/'--out-format' is not allowed", customOptionsField);
            } else {
                modified = true;
                return null;
            }
        }).installOn(customOptionsField);

        customOptionsField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                ComponentValidator.getInstance(customOptionsField).ifPresent(ComponentValidator::revalidate);
            }
        });

        lintersInPath = getLinterFromPath();

        configFile = GoLinterLocalInspection.Companion.findCustomConfigInPath(curProject.getBasePath());
        if (!configFile.isEmpty()) {
            // found an valid config file
            configFileHintLabel = new LinkLabel<String>(
                    String.format("Using %s", configFile), null, (aSource, aLinkData) -> {
                try {
                    Desktop.getDesktop().open(new File(configFile));
                } catch (Exception e) {
                    // ignore
                }
            });
            linterTable.setEnabled(false);
        } else {
            JCheckBox tmp = new JCheckBox("I'm using custom config file");
            tmp.setForeground(new JBColor(0xffc107, 0xffc107));
            tmp.addActionListener(e -> linterTable.setEnabled(!buttonActionPerformed(e)));
            tmp.setSelected(GoLinterConfig.INSTANCE.getUseConfigFile());
            configFileHintLabel = tmp;
        }
    }

    // refresh lintersTable with selected item of linterComboBox
    private void refreshTableContent() {
        DefaultTableModel model = (DefaultTableModel) linterTable.getModel();
        model.setRowCount(0);

        if (allLinters != null) {
            for (GoLinter linter : allLinters) {
                model.addRow(new Object[]{
                        new Pair<>(enabledLinters.contains(linter.getName()), linter.getName()),
                        linter.getDescription()
                });
            }
        }
    }

    // if `selected` not in combo list, add it into combo list
    // set combo box to `selected`
    @SuppressWarnings("unchecked")
    private void setLinterExecutables(@NotNull String selected) {
        Set<String> items;

        if (!lintersInPath.contains(selected)) {
            items = (HashSet<String>) lintersInPath.clone();
            items.add(selected);
        } else items = lintersInPath;

        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(items.toArray(new String[0]));
        linterChooseComboBox.setModel(model);
        linterChooseComboBox.setSelectedItem(selected);
    }

    // return path of golangci-lint in PATH
    private HashSet<String> getLinterFromPath() {
        HashSet<String> rst = new HashSet<>();
        String pathStr = System.getenv("PATH");
        if (pathStr != null) {
            Set<String> paths = new HashSet<>(Arrays.asList(pathStr.split(File.pathSeparator)));
            // special case: downloaded previously by this plugin
            paths.add(UtilitiesKt.getExecutionDir());

            for (String path : paths) {
                Path fullPath = Paths.get(path, UtilitiesKt.getLinterExecutableName());
                if (fullPath.toFile().canExecute()) {
                    rst.add(fullPath.toString());
                }
            }
        }

        return rst;
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
        GoLinterConfig.INSTANCE.setUseCustomOptions(useCustomOptionsCheckBox.isSelected());
        GoLinterConfig.INSTANCE.setCustomOptions(customOptionsField.getText());
        if (configFileHintLabel instanceof JCheckBox) {
            GoLinterConfig.INSTANCE.setUseConfigFile(((JCheckBox)configFileHintLabel).isSelected());
        }

        GoLinterConfig.INSTANCE.setEnabledLinters(enabledLinters.toArray(new String[0]));
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
        linterPathSelected = GoLinterConfig.INSTANCE.getGoLinterExe();
        if (!linterPathSelected.equals(linterChooseComboBox.getSelectedItem()))
            setLinterExecutables(linterPathSelected);

        // force refresh to get rid of enabled / disabled linters change
        refreshTableContent();

        if (configFileHintLabel instanceof JCheckBox) {
            ((JCheckBox)configFileHintLabel).setSelected(GoLinterConfig.INSTANCE.getUseConfigFile());
            linterTable.setEnabled(!GoLinterConfig.INSTANCE.getUseConfigFile());
        }

        customOptionsField.setText(GoLinterConfig.INSTANCE.getCustomOptions());
        useCustomOptionsCheckBox.setSelected(GoLinterConfig.INSTANCE.getUseCustomOptions());
        customOptionsField.setEnabled(GoLinterConfig.INSTANCE.getUseCustomOptions());

        modified = false;
    }

    @Override
    public void disposeUIResources() {
        Disposer.dispose(this);
    }

    @Override
    public void dispose() {
        UIUtil.dispose(this.linterChooseComboBox);
        UIUtil.dispose(this.fetchLatestReleaseButton);
        UIUtil.dispose(this.useCustomOptionsCheckBox);
        UIUtil.dispose(this.customOptionsField);
        UIUtil.dispose(this.configFileHintLabel);
        UIUtil.dispose(this.helpDocumentLabel);
        UIUtil.dispose(this.linterSelectPanel);
        UIUtil.dispose(this.refreshProcessIcon);
        UIUtil.dispose(this.linterTable);
        this.linterChooseComboBox = null;
        this.fetchLatestReleaseButton = null;
        this.useCustomOptionsCheckBox = null;
        this.customOptionsField = null;
        this.configFileHintLabel = null;
        this.helpDocumentLabel = null;
        this.linterSelectPanel = null;
        this.refreshProcessIcon = null;
        this.linterTable = null;
    }

    //----------------------------- ActionListeners -----------------------------
    private void linterSelected(ActionEvent e) {
        // selection changed
        modified = true;
        linterPathSelected = (String) linterChooseComboBox.getSelectedItem();

        // refresh linters
        CardLayout cl = (CardLayout) linterSelectPanel.getLayout();
        refreshProcessIcon.resume();
        cl.show(linterSelectPanel, "refreshProcessIcon");

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            allLinters = null;
            enabledLinters = null;

            if (new File(linterPathSelected).canExecute()) {
                allLinters = GolangCiOutputParser.INSTANCE.parseLinters(
                        GolangCiOutputParser.INSTANCE.runProcess(Arrays.asList(linterPathSelected, "linters"), curProject.getBasePath(), new HashMap<>())
                );

                String[] enabledLintersInConfig = GoLinterConfig.INSTANCE.getEnabledLinters();
                if (enabledLintersInConfig != null && configFile.isEmpty()) {
                    // previously selected linters
                    enabledLinters = Arrays.stream(enabledLintersInConfig).collect(Collectors.toSet());
                } else {
                    // default enabled linters
                    enabledLinters = allLinters.stream().filter(GoLinter::getDefaultEnabled).map(GoLinter::getName).collect(Collectors.toSet());
                }
            }

            linterTableHeader.switchState(allLinters == null || enabledLinters.size() != allLinters.size());

            cl.show(linterSelectPanel, "lintersTable");
            refreshProcessIcon.suspend();

            refreshTableContent();
        });
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

        VirtualFile file = FileChooser.chooseFile(
                fileChooserDescriptor,
                GoLinterSettings.this.settingPanel,
                null,
                curSelected);

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
            }, "Get Latest Release", true, curProject));
            modified = true;
        } catch (ProcessCanceledException ex) {
            UtilitiesKt.getLogger().info("get latest golangci-lint cancelled");
        } catch (Exception ex) {
            JLabel messageLabel = new JBLabel(ex.getMessage(),
                    new ImageIcon(new ImageIcon(this.getClass().getResource("/images/mole.png")).getImage().getScaledInstance(60, 60, Image.SCALE_SMOOTH)),
                    SwingConstants.CENTER);
            messageLabel.setBorder(JBUI.Borders.empty(10));
            messageLabel.setIconTextGap(20);
            DialogBuilder builder = new DialogBuilder(settingPanel).title("Failed to get latest release").centerPanel(messageLabel).resizable(false);
            builder.removeAllActions();
            builder.addOkAction();
            builder.show();
            builder.dispose();
            UIUtil.dispose(messageLabel);
        }
    }

    public boolean buttonActionPerformed(ActionEvent actionEvent) {
        modified = true;
        AbstractButton abstractButton = (AbstractButton) actionEvent.getSource();
        return abstractButton.getModel().isSelected();
    }
}
