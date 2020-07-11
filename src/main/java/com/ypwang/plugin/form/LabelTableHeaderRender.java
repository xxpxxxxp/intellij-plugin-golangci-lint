package com.ypwang.plugin.form;

import com.intellij.icons.AllIcons;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

public class LabelTableHeaderRender extends JLabel implements TableCellRenderer {

    private final JTable table;
    private final Consumer<Boolean> statusListener;
    private boolean isEnableAllStatus;

    public LabelTableHeaderRender(JTable table, Consumer<Boolean> statusListener) {
        table.getTableHeader().addMouseListener(new MouseHandler());

        this.table = table;
        this.statusListener = statusListener;

        this.setBorder(JBUI.Borders.emptyLeft(8));
        // make sure label is initialized
        switchState(!isEnableAllStatus);
        this.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    @Override
    public Component getTableCellRendererComponent(JTable jTable, Object o, boolean b, boolean b1, int i, int i1) {
        this.setEnabled(jTable.isEnabled());
        return this;
    }

    public void switchState(boolean status) {
        if (this.isEnableAllStatus != status) {
            if (status) {
                this.setIcon(AllIcons.Actions.Selectall);
                this.setText("Enable All");
            } else {
                this.setIcon(AllIcons.Actions.Unselectall);
                this.setText("Disable All");
            }

            this.table.getTableHeader().repaint();
            this.isEnableAllStatus = status;
        }
    }

    private class MouseHandler extends MouseAdapter {
        @Override
        public void mouseClicked(MouseEvent e) {
            if (table.isEnabled()) {
                Point point = e.getPoint();
                int column = table.columnAtPoint(point);
                if (column == 0) {
                    statusListener.accept(isEnableAllStatus);
                    switchState(!isEnableAllStatus);
                }
            }
        }
    }
}
