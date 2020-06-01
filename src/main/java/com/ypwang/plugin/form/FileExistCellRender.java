package com.ypwang.plugin.form;

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