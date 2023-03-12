package com.ypwang.plugin.form;

import com.intellij.execution.wsl.WslPath;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.ui.JBColor;
import com.ypwang.plugin.UtilitiesKt;
import com.ypwang.plugin.platform.Platform;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.io.File;

class FileExistCellRender extends DefaultListCellRenderer {
    private Project project;

    FileExistCellRender(@NotNull Project project) {
        this.project = project;
        setOpaque(true);
    }

    @Override
    public Component getListCellRendererComponent(JList jc, Object val, int idx, boolean isSelected, boolean cellHasFocus) {
        setText(null);
        setForeground(JBColor.BLACK);
        setIcon(null);

        if (val != null) {
            String path = val.toString();
            setText(path);
            if (path.isEmpty() && UtilitiesKt.findCustomConfigInPath(project.getBasePath()).isPresent()) {
                String defaultExe = Platform.Companion.platformFactory(project).getDefaultExecutable();
                if (!defaultExe.isEmpty()) {
                    setText("(assume)" + defaultExe);
                }
            }

            if (SystemInfo.isWindows && WslPath.isWslUncPath(path))
                setIcon(new ImageIcon(new ImageIcon(this.getClass().getResource("/images/wsl.png")).getImage().getScaledInstance(20, 20, Image.SCALE_SMOOTH)));
            else if (!new File(path).canExecute()) {
                setForeground(JBColor.RED);
            }
        }
        return this;
    }
}