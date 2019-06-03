package com.ypwang.plugin.form;

import com.goide.sdk.GoSdkService;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.SearchableConfigurable;
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
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.*;
import com.ypwang.plugin.*;
import com.ypwang.plugin.util.Log;
import kotlin.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.*;
import java.util.List;

import com.ypwang.plugin.util.ProcessWrapper;

public class GoLinterSettings implements SearchableConfigurable, Disposable {
    private JPanel settingPanel;
    private JComboBox linterComboBox;
    private JButton linterChooseButton;
    private JButton goGetButton;
    private JCheckBox useCustomOptionsCheckBox;
    private JTextField customOptionsField;
    private JCheckBox useConfigFileCheckBox;
    private JLabel configFileHintLabel;
    private JPanel linterSelectPanel;
    private AsyncProcessIcon.Big refreshProcessIcon;
    private JTable lintersTable;

    private boolean modified = false;
    private HashSet<String> lintersInPath;
    private Project curProject;

    public GoLinterSettings(@NotNull Project project) {
        curProject = project;

        linterComboBox.addActionListener(this::linterSelected);
        linterChooseButton.addActionListener(this::linterChoosed);
        goGetButton.addActionListener(this::goGet);
        useCustomOptionsCheckBox.addActionListener(this::useCustomOptionsChecked);
        useConfigFileCheckBox.addActionListener(this::useConfigFileChecked);
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

        if (!selected.isEmpty()) linterComboBox.setSelectedItem(selected);
        // trigger table change
        else {
            linterComboBox.setSelectedIndex(-1);
            refreshLinterTable();
        }
    }

    private void refreshLinterTable() {
        CardLayout cl = (CardLayout) linterSelectPanel.getLayout();
        refreshProcessIcon.resume();
        cl.show(linterSelectPanel, "refreshProcessIcon");

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (linterComboBox.getSelectedItem() != null) {
                DefaultTableModel model = (DefaultTableModel)lintersTable.getModel();
                for (int i = model.getRowCount() - 1; i > -1; i--) {
                    model.removeRow(i);
                }

                GoSupportedLinters extractedLinters = GoSupportedLinters.Companion.getInstance(linterComboBox.getSelectedItem().toString());
                List<Pair<String, String>> allLinters = new LinkedList<>(extractedLinters.getDefaultEnabledLinters());
                allLinters.addAll(extractedLinters.getDefaultDisabledLinters());

                Map<String, String> enabledLinters = new HashMap<>();
                String[] enabledLintersInConfig = GoLinterConfig.INSTANCE.getEnabledLinters();
                if (enabledLintersInConfig != null) {
                    for (String linter: enabledLintersInConfig) {
                        enabledLinters.put(linter, "");
                    }
                } else {
                    for (Pair<String, String> p: extractedLinters.getDefaultEnabledLinters()) {
                        enabledLinters.put(p.component1(), p.component2());
                    }
                }

                for (Pair<String, String> linter: allLinters) {
                    model.addRow(new Object[]{
                            enabledLinters.containsKey(linter.component1()),
                            linter.component1(),
                            linter.component2()
                    });
                }
            }

            cl.show(linterSelectPanel, "lintersTable");
            refreshProcessIcon.suspend();
        });
    }

    private void createUIComponents() {
        // initialize components
        linterComboBox = new ComboBox();
        goGetButton = new JButton();
        useCustomOptionsCheckBox = new JCheckBox();
        customOptionsField = new JTextField();
        configFileHintLabel = new JLabel();
        useConfigFileCheckBox = new JCheckBox();
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
        new ComponentValidator(curProject).withValidator(v -> {
            String text = customOptionsField.getText();
            if (text.contains("-E") || text.contains("-D")) {
                v.updateInfo(new ValidationInfo("Please enable/disable linters in table below", customOptionsField));
                modified = false;
            } else if (text.contains("-v") || text.contains("--out-format")) {
                v.updateInfo(new ValidationInfo("'--verbose'/'--out-format' is not allowed", customOptionsField));
                modified = false;
            } else {
                v.updateInfo(null);
                modified = true;
            }
        }).installOn(customOptionsField);

        customOptionsField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                ComponentValidator.getInstance(customOptionsField).ifPresent(ComponentValidator::revalidate);
            }
        });

        lintersInPath = new HashSet<>();
        // find if there's golangci-lint in system path
        String systemPath = System.getenv("PATH");
        if (systemPath != null) {
            String[] paths = systemPath.split(File.pathSeparator);

            for (String path : paths) {
                String fullPath = path + '/' + PlatformSettings.INSTANCE.getLinterExecutableName();
                if (new File(fullPath).isFile()) {
                    lintersInPath.add(fullPath);
                }
            }
        }

        HashSet<String> lintersInGoPath = refreshLinterFromGoPath();
        lintersInPath.addAll(lintersInGoPath);

        // go get is only enabled if there's no golangci-lint in GOPATH
        if (!lintersInGoPath.isEmpty()) goGetButton.setEnabled(false);
    }

    // return path of golangci-lint in GOPATH
    private HashSet<String> refreshLinterFromGoPath() {
        String goPath = System.getenv("GOPATH");
        HashSet<String> rst = new HashSet<>();

        if (goPath != null) {
            for (String path: goPath.split(File.pathSeparator)) {
                String fullPath = String.format("%s/bin/%s", path, PlatformSettings.INSTANCE.getLinterExecutableName());
                if (new File(fullPath).isFile()) {
                    rst.add(fullPath);
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
    public String getDisplayName(){
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
        GoLinterConfig.INSTANCE.setCustomOptions(customOptionsField.getText().split(" "));
        GoLinterConfig.INSTANCE.setUseConfigFile(useConfigFileCheckBox.isSelected());

        LinkedList<String> enabledLinters = new LinkedList<>();
        for (int row = 0; row < lintersTable.getRowCount(); row++){
            if ((boolean) lintersTable.getValueAt(row, 0)) {
                enabledLinters.add((String) lintersTable.getValueAt(row, 1));
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
        modified = false;

        setLinterExecutables(GoLinterConfig.INSTANCE.getGoLinterExe());

        customOptionsField.setText(String.join(" ", GoLinterConfig.INSTANCE.getCustomOptions()));
        useCustomOptionsCheckBox.setSelected(GoLinterConfig.INSTANCE.getUseCustomOptions());
        customOptionsField.setEnabled(GoLinterConfig.INSTANCE.getUseCustomOptions());

        useConfigFileCheckBox.setSelected(GoLinterConfig.INSTANCE.getUseConfigFile());
        configFileHintLabel.setVisible(GoLinterConfig.INSTANCE.getUseConfigFile());
        lintersTable.setEnabled(!GoLinterConfig.INSTANCE.getUseConfigFile());
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
        UIUtil.dispose(this.useConfigFileCheckBox);
        UIUtil.dispose(this.configFileHintLabel);
        UIUtil.dispose(this.linterSelectPanel);
        UIUtil.dispose(this.refreshProcessIcon);
        UIUtil.dispose(this.lintersTable);
        this.linterComboBox = null;
        this.goGetButton = null;
        this.useCustomOptionsCheckBox = null;
        this.customOptionsField = null;
        this.useConfigFileCheckBox = null;
        this.configFileHintLabel = null;
        this.linterSelectPanel = null;
        this.refreshProcessIcon = null;
        this.lintersTable = null;
    }

    //----------------------------- ActionListeners -----------------------------
    private void linterSelected(ActionEvent e) {
        if (!GoLinterConfig.INSTANCE.getGoLinterExe().equals(linterComboBox.getSelectedItem()))
            modified = true;
        refreshLinterTable();
    }

    private void linterChoosed(ActionEvent e) {
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
            if (curSelected == null || !file.getPath().equals(curSelected.getPath()))
                modified = true;

            setLinterExecutables(file.getPath());
        }
    }

    private void showDialog(String title, String message) {
        JLabel messageLabel = new JBLabel(message,
                new ImageIcon(new ImageIcon(this.getClass().getResource("/images/mouse.png")).getImage().getScaledInstance(60, 60, Image.SCALE_SMOOTH)),
                JBLabel.HORIZONTAL);
        messageLabel.setBorder(JBUI.Borders.empty(10));
        messageLabel.setIconTextGap(20);
        DialogBuilder builder = new DialogBuilder(settingPanel).title(title).centerPanel(messageLabel).resizable(false);
        builder.removeAllActions();
        builder.addOkAction();
        builder.show();
        builder.dispose();
        UIUtil.dispose(messageLabel);
    }

    private void goGet(ActionEvent e) {
        try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
                String goRoot = GoSdkService.getInstance(curProject).getSdk(null).getHomePath();
                Log.INSTANCE.getGolinter().info("GOROOT is " + goRoot);

                if (goRoot.isEmpty()) {
                    throw new Exception("Please set GOROOT in Go plugin");
                }

                String goExecutablePath = goRoot + "/bin/" + PlatformSettings.INSTANCE.getGoExecutableName();
                if (!new File(goExecutablePath).isFile()) {
                    throw new Exception("Cannot find Go executable in GOROOT");
                }

                ProgressIndicator progressIndicator = ProgressManager.getInstance().getProgressIndicator();
                progressIndicator.setIndeterminate(true);
                List<String> arguments = new LinkedList<>();
                arguments.add(goExecutablePath);
                arguments.add("get");
                arguments.add("-u");
                arguments.add("github.com/golangci/golangci-lint/cmd/golangci-lint");

                if (ProcessWrapper.INSTANCE.runWithArguments(arguments, null).component1() != 0) {
                    throw new Exception("Failed to get golangci-lint");
                }

                return true;
            }, "Go Get From Github", false, curProject);
        } catch (Exception ex) {
            showDialog("go get golangci-lint", ex.getMessage());
        }

        HashSet<String> lintersInGoPath = refreshLinterFromGoPath();
        lintersInPath.addAll(lintersInGoPath);
        if (!lintersInGoPath.isEmpty()) {
            goGetButton.setEnabled(false);
            String path = lintersInGoPath.iterator().next();
            boolean sameAsSelected = path.equals(linterComboBox.getSelectedItem());
            setLinterExecutables(path);

            // If the go get path is previously selected, we need to manually refresh the table
            // Because re-select it won't trigger fire select event
            if (sameAsSelected) {
                refreshLinterTable();
            }
        }
    }

    private void useCustomOptionsChecked(ActionEvent e) {
        boolean selected = useCustomOptionsCheckBox.isSelected();
        customOptionsField.setEnabled(selected);
        modified = true;
    }

    private void useConfigFileChecked(ActionEvent e) {
        boolean selected = useConfigFileCheckBox.isSelected();
        configFileHintLabel.setVisible(selected);
        lintersTable.setEnabled(!selected);
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
            if (new File(path).isFile()) setForeground(JBColor.BLACK);
            else setForeground(JBColor.RED);
        }
        return this;
    }
}