package com.adelylria.ringlog.ui.components;

import com.adelylria.ringlog.ui.theme.UiKit;
import com.adelylria.ringlog.ui.theme.SurfacePanel;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.JLabel;
import javax.swing.JPanel;

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

    public boolean isEmpty() {
        return rowIndex == 0;
    }
}
