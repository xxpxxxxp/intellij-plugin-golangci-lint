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
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.*;
import com.ypwang.plugin.*;
import com.ypwang.plugin.model.GoLinter;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class GoLinterSettings implements SearchableConfigurable, Disposable {
    private JPanel settingPanel;
    private JComboBox<String> linterComboBox;
    private JButton linterChooseButton;
    private JButton goGetButton;
    private JCheckBox useCustomOptionsCheckBox;
    private JTextField customOptionsField;
    private JLabel configFileHintLabel;
    private JPanel linterSelectPanel;
    private JLabel helpLabel;
    private AsyncProcessIcon.Big refreshProcessIcon;
    private JTable lintersTable;

    private boolean modified = false;
    private HashSet<String> lintersInPath;
    private Project curProject;

    private String selectedLinter;

    public GoLinterSettings(@NotNull Project project) {
        curProject = project;

        linterComboBox.addActionListener(e -> linterSelected());
        linterChooseButton.addActionListener(e -> linterChoose());
        goGetButton.addActionListener(e -> goGet());
        useCustomOptionsCheckBox.addActionListener(e -> useCustomOptionsChecked());
    }

    private void createUIComponents() {
        // initialize components
        linterComboBox = new ComboBox<>();
        goGetButton = new JButton();
        useCustomOptionsCheckBox = new JCheckBox();
        customOptionsField = new JTextField();
        configFileHintLabel = new JLabel("To use custom config, put the conf file in project directory");
        helpLabel = new LinkLabel<String>(null, null, (aSource, aLinkData) -> {
            try {
                Desktop.getDesktop().browse(new URL("https://github.com/golangci/golangci-lint#config-file").toURI());
            } catch (Exception e) {
                // ignore
            }
        });
        linterSelectPanel = new JPanel(new CardLayout());
        lintersTable = new JBTable();
        refreshProcessIcon = new AsyncProcessIcon.Big("progress");
        lintersTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        linterSelectPanel.add("lintersTable", new JBScrollPane(lintersTable));
        linterSelectPanel.add("refreshProcessIcon", refreshProcessIcon);

        linterComboBox.setRenderer(new FileExistCellRender());

        DefaultTableModel model = new DefaultTableModel(new String[]{"Enabled", "Name", "Description"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 0) return Boolean.class;
                else return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0;
            }
        };
        model.addTableModelListener(e -> modified = true);
        lintersTable.setModel(model);
        lintersTable.getColumnModel().getColumn(0).setPreferredWidth(15);
        lintersTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        lintersTable.getColumnModel().getColumn(2).setPreferredWidth(400);

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

        String configFile = GoLinterLocalInspection.Companion.findCustomConfig(curProject);
        if (!configFile.isEmpty()) {
            // found an valid config file
            UIUtil.dispose(configFileHintLabel);
            configFileHintLabel = new LinkLabel<String>(
                    String.format("Using %s", configFile), null, (aSource, aLinkData) -> {
                try {
                    Desktop.getDesktop().open(new File(configFile));
                } catch (Exception e) {
                    // ignore
                }
            });
            lintersTable.setEnabled(false);
        }
    }

    private void setLinterExecutables(String selected) {
        HashSet<String> items;

        if (!selected.isEmpty() && !lintersInPath.contains(selected)) {
            //noinspection unchecked
            items = (HashSet<String>) lintersInPath.clone();
            items.add(selected);
        } else items = lintersInPath;

        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(items.toArray(new String[0]));
        linterComboBox.setModel(model);

        if (!selected.isEmpty())
            linterComboBox.setSelectedItem(selected);
        else
            linterComboBox.setSelectedIndex(-1);    // trigger table change
    }

    // refresh {@link lintersTable} with selected item of {@link linterComboBox}
    private synchronized void refreshLinterTable() {
        CardLayout cl = (CardLayout) linterSelectPanel.getLayout();
        refreshProcessIcon.resume();
        cl.show(linterSelectPanel, "refreshProcessIcon");

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (linterComboBox.getSelectedItem() != null) {
                DefaultTableModel model = (DefaultTableModel) lintersTable.getModel();
                model.setRowCount(0);

                String selected = (String) linterComboBox.getSelectedItem();
                if (new File(selected).canExecute()) {
                    List<GoLinter> allLinters = GoSupportedLinters.Companion.getInstance(selected).getLinters();

                    Set<String> enabledLinters;
                    String[] enabledLintersInConfig = GoLinterConfig.INSTANCE.getEnabledLinters();
                    if (enabledLintersInConfig != null)
                        // previously selected linters
                        enabledLinters = Arrays.stream(enabledLintersInConfig).collect(Collectors.toSet());
                    else
                        // default enabled linters
                        enabledLinters = allLinters.stream().filter(GoLinter::getDefaultEnabled).map(GoLinter::getName).collect(Collectors.toSet());

                    for (GoLinter linter : allLinters) {
                        model.addRow(new Object[]{
                                enabledLinters.contains(linter.getName()),
                                linter.getFullName(),
                                linter.getDescription()
                        });
                    }
                }
            }

            cl.show(linterSelectPanel, "lintersTable");
            refreshProcessIcon.suspend();
        });
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
        if (linterComboBox.getSelectedItem() != null) {
            GoLinterConfig.INSTANCE.setGoLinterExe((String) linterComboBox.getSelectedItem());
        }
        GoLinterConfig.INSTANCE.setUseCustomOptions(useCustomOptionsCheckBox.isSelected());
        GoLinterConfig.INSTANCE.setCustomOptions(customOptionsField.getText());

        LinkedList<String> enabledLinters = new LinkedList<>();
        for (int row = 0; row < lintersTable.getRowCount(); row++) {
            if ((boolean) lintersTable.getValueAt(row, 0)) {
                String linter = (String) lintersTable.getValueAt(row, 1);
                int idx = linter.indexOf(' ');
                if (idx > 0)
                    linter = linter.substring(0, idx);
                enabledLinters.add(linter);
            }
        }

        GoLinterConfig.INSTANCE.setEnabledLinters(enabledLinters.toArray(new String[0]));
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
        setLinterExecutables(GoLinterConfig.INSTANCE.getGoLinterExe());
        refreshLinterTable();

        String t = "";
        if (GoLinterConfig.INSTANCE.getCustomOptions() != null)
            t = GoLinterConfig.INSTANCE.getCustomOptions();
        customOptionsField.setText(t);

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
        UIUtil.dispose(this.linterComboBox);
        UIUtil.dispose(this.goGetButton);
        UIUtil.dispose(this.useCustomOptionsCheckBox);
        UIUtil.dispose(this.customOptionsField);
        UIUtil.dispose(this.configFileHintLabel);
        UIUtil.dispose(this.helpLabel);
        UIUtil.dispose(this.linterSelectPanel);
        UIUtil.dispose(this.refreshProcessIcon);
        UIUtil.dispose(this.lintersTable);
        this.linterComboBox = null;
        this.goGetButton = null;
        this.useCustomOptionsCheckBox = null;
        this.customOptionsField = null;
        this.configFileHintLabel = null;
        this.helpLabel = null;
        this.linterSelectPanel = null;
        this.refreshProcessIcon = null;
        this.lintersTable = null;
    }

    //----------------------------- ActionListeners -----------------------------
    // I don't know why but this ActionListener fire 3 times every selection
    private void linterSelected() {
        if (selectedLinter == null || !selectedLinter.equals(linterComboBox.getSelectedItem())) {
            selectedLinter = (String) linterComboBox.getSelectedItem();
            if (!GoLinterConfig.INSTANCE.getGoLinterExe().equals(linterComboBox.getSelectedItem()))
                modified = true;
            refreshLinterTable();
        }
    }

    private void linterChoose() {
        FileChooserDescriptor fileChooserDescriptor = new FileChooserDescriptor(true, false, false, false, false, false);

        if (SystemInfo.isWindows) {
            fileChooserDescriptor.withFileFilter((VirtualFile file) -> "exe".equalsIgnoreCase(file.getExtension()));
        }

        VirtualFile curSelected = null;
        if (linterComboBox.getSelectedItem() != null) {
            curSelected = LocalFileSystem.getInstance().findFileByPath((String) linterComboBox.getSelectedItem());
        }

        VirtualFile file = FileChooser.chooseFile(
                fileChooserDescriptor,
                GoLinterSettings.this.settingPanel,
                null,
                curSelected);

        if (file != null) {
            // because `file.getPath()` returns linux path, seems wired on windows
            String systemPath = Paths.get(file.getPath()).toString();
            if (curSelected == null || !systemPath.equals(curSelected.getPath()))
                modified = true;

            setLinterExecutables(systemPath);
        }
    }

    private void showDialog(String title, String message) {
        JLabel messageLabel = new JBLabel(message,
                new ImageIcon(new ImageIcon(this.getClass().getResource("/images/mouse.png")).getImage().getScaledInstance(60, 60, Image.SCALE_SMOOTH)),
                SwingConstants.HORIZONTAL);
        messageLabel.setBorder(JBUI.Borders.empty(10));
        messageLabel.setIconTextGap(20);
        DialogBuilder builder = new DialogBuilder(settingPanel).title(title).centerPanel(messageLabel).resizable(false);
        builder.removeAllActions();
        builder.addOkAction();
        builder.show();
        builder.dispose();
        UIUtil.dispose(messageLabel);
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
            showDialog("Failed to get latest release", ex.getMessage());
        }
    }

    private void useCustomOptionsChecked() {
        boolean selected = useCustomOptionsCheckBox.isSelected();
        customOptionsField.setEnabled(selected);
        modified = true;
    }
}

class FileExistCellRender extends DefaultListCellRenderer {
    FileExistCellRender() {
        setOpaque(true);
    }

    @Override
    public Component getListCellRendererComponent(JList jc, Object val, int idx, boolean isSelected, boolean cellHasFocus) {
        if (val == null) setForeground(JBColor.BLACK);
        else {
            String path = val.toString();
            setText(path);
            if (new File(path).canExecute()) setForeground(JBColor.BLACK);
            else setForeground(JBColor.RED);
        }
        return this;
    }
}