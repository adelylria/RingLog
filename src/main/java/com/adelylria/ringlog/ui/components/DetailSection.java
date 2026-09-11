package com.adelylria.ringlog.ui.components;

import com.adelylria.ringlog.ui.theme.UiKit;
import com.adelylria.ringlog.ui.theme.SurfacePanel;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

public class DetailSection extends SurfacePanel {

    private final JPanel rows;
    private int rowIndex;

    public DetailSection(String title) {
        super(new BorderLayout(0, 12));
        setBorder(UiKit.cardBorder());

        JLabel titleLabel = UiKit.valueLabel(title);
        add(titleLabel, BorderLayout.NORTH);

        rows = new JPanel(new GridBagLayout());
        rows.setOpaque(false);
        add(rows, BorderLayout.CENTER);
    }

    public void addRow(String label, Object value) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = rowIndex;
        labelConstraints.weightx = 0.35;
        labelConstraints.fill = GridBagConstraints.HORIZONTAL;
        labelConstraints.anchor = GridBagConstraints.NORTHWEST;
        labelConstraints.insets = new Insets(4, 0, 4, 14);

        GridBagConstraints valueConstraints = new GridBagConstraints();
        valueConstraints.gridx = 1;
        valueConstraints.gridy = rowIndex++;
        valueConstraints.weightx = 0.65;
        valueConstraints.fill = GridBagConstraints.HORIZONTAL;
        valueConstraints.anchor = GridBagConstraints.NORTHWEST;
        valueConstraints.insets = new Insets(4, 0, 4, 0);

        JLabel labelComponent = UiKit.muted(label);
        JLabel valueComponent = UiKit.valueLabel(UiKit.display(value));
        rows.add(labelComponent, labelConstraints);
        rows.add(valueComponent, valueConstraints);
    }

    public void addOptionalRow(String label, Object value) {
        if (value == null) {
            return;
        }
        String text = value.toString();
        if (text.isBlank() || "—".equals(text)) {
            return;
        }
        addRow(label, value);
    }

    public JTextArea addTextBlock(Object value) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = rowIndex++;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = new Insets(4, 0, 4, 0);

        JTextArea text = new WrappingTextArea(UiKit.display(value));
        rows.add(text, constraints);
        return text;
    }

    public JTextArea addWrappingRow(String label, Object value) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = rowIndex;
        labelConstraints.weightx = 0.35;
        labelConstraints.fill = GridBagConstraints.HORIZONTAL;
        labelConstraints.anchor = GridBagConstraints.NORTHWEST;
        labelConstraints.insets = new Insets(4, 0, 4, 14);

        GridBagConstraints valueConstraints = new GridBagConstraints();
        valueConstraints.gridx = 1;
        valueConstraints.gridy = rowIndex++;
        valueConstraints.weightx = 0.65;
        valueConstraints.fill = GridBagConstraints.HORIZONTAL;
        valueConstraints.anchor = GridBagConstraints.NORTHWEST;
        valueConstraints.insets = new Insets(4, 0, 4, 0);

        JTextArea text = new WrappingTextArea(UiKit.display(value));
        text.setFont(text.getFont().deriveFont(Font.BOLD));
        rows.add(UiKit.muted(label), labelConstraints);
        rows.add(text, valueConstraints);
        return text;
    }

    public void addAside(Component component) {
        remove(rows);
        JPanel columns = new JPanel(new GridLayout(1, 2, 24, 0));
        columns.setOpaque(false);
        columns.add(rows);
        columns.add(component);
        add(columns, BorderLayout.CENTER);
    }

    public boolean isEmpty() {
        return rowIndex == 0;
    }

    private static final class WrappingTextArea extends JTextArea {

        private static final int FALLBACK_WIDTH = 480;

        private WrappingTextArea(String text) {
            super(text);
            setEditable(false);
            setFocusable(false);
            setLineWrap(true);
            setWrapStyleWord(true);
            setOpaque(false);
            setBorder(new EmptyBorder(0, 0, 0, 0));
            setMargin(new Insets(0, 0, 0, 0));
            setRows(1);
            setColumns(1);

            Font labelFont = UIManager.getFont("Label.font");
            if (labelFont != null) {
                setFont(labelFont.deriveFont(Font.PLAIN, 14f));
            }
            setForeground(UiKit.textColor());
        }

        @Override
        public Dimension getPreferredSize() {
            int availableWidth = getWidth();
            if (availableWidth <= 0 && getParent() != null) {
                Insets insets = getParent().getInsets();
                availableWidth = getParent().getWidth() - insets.left - insets.right;
            }
            if (availableWidth <= 0) {
                availableWidth = FALLBACK_WIDTH;
            }

            super.setSize(availableWidth, Short.MAX_VALUE);
            Dimension preferred = super.getPreferredSize();
            return new Dimension(availableWidth, preferred.height);
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, getPreferredSize().height);
        }
    }
}
