package com.ypwang.plugin.form;

import com.intellij.openapi.util.Pair;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.ItemListener;
import java.awt.event.MouseEvent;
import java.util.EventObject;

class CheckBoxCellEditor extends AbstractCellEditor implements TableCellEditor, TableCellRenderer {

    // keep editor state
    private final transient ItemListener listener;
    private final JCheckBox checkBox = new JBCheckBox();
    private final transient Border leftPadding = JBUI.Borders.emptyLeft(8);

    CheckBoxCellEditor(ItemListener listener) {
        this.listener = listener;
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
        @SuppressWarnings("unchecked")
        Pair<Boolean, String> p = (Pair<Boolean, String>) value;
        JCheckBox cb = new JBCheckBox(p.getSecond(), p.getFirst());

        if (isSelected) {
            cb.setForeground(table.getSelectionForeground());
            cb.setBackground(table.getSelectionBackground());
        } else {
            cb.setForeground(table.getForeground());
            cb.setBackground(table.getBackground());
        }

        Border border = leftPadding;
        if (hasFocus) {
            border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(JBColor.border()), border);
        }

        cb.setBorder(border);
        return cb;
    }

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
        @SuppressWarnings("unchecked")
        Pair<Boolean, String> p = (Pair<Boolean, String>) value;
        checkBox.removeItemListener(listener);
        checkBox.setSelected(p.getFirst());
        checkBox.setText(p.getSecond());
        checkBox.setBorder(leftPadding);

        checkBox.setForeground(table.getSelectionForeground());
        checkBox.setBackground(table.getSelectionBackground());

        checkBox.addItemListener(listener);

        return checkBox;
    }

    @Override
    public Object getCellEditorValue() {
        return new Pair<>(checkBox.isSelected(), checkBox.getText());
    }

    @Override
    public boolean isCellEditable(EventObject anEvent) {
        return anEvent instanceof MouseEvent;
    }

    @Override
    public boolean shouldSelectCell(EventObject anEvent) {
        return true;
    }

    @Override
    public boolean stopCellEditing() {
        fireEditingStopped();
        return true;
    }

    @Override
    public void cancelCellEditing() {
        fireEditingCanceled();
    }
}