package com.ypwang.plugin.form;

import com.intellij.execution.wsl.WslPath;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.ui.JBColor;

import javax.swing.*;
import java.awt.*;
import java.io.File;

class FileExistCellRender extends DefaultListCellRenderer {
    FileExistCellRender() {
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

            if (SystemInfo.isWindows && WslPath.isWslUncPath(path))
                setIcon(new ImageIcon(new ImageIcon(this.getClass().getResource("/images/wsl.png")).getImage().getScaledInstance(20, 20, Image.SCALE_SMOOTH)));
            else if (!new File(path).canExecute()) {
                setForeground(JBColor.RED);
            }
        }
        return this;
    }
}