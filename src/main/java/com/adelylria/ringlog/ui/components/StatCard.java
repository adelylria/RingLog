package com.adelylria.ringlog.ui.components;

import com.adelylria.ringlog.ui.theme.UiKit;
import com.adelylria.ringlog.ui.theme.SurfacePanel;

import java.awt.BorderLayout;
import java.awt.Font;

import javax.swing.JLabel;
import javax.swing.JPanel;

public class StatCard extends SurfacePanel {

    public StatCard(String label, String value, String caption) {
        super(new BorderLayout(0, 8));
        setBorder(UiKit.cardBorder());

        JLabel labelComponent = UiKit.eyebrow(label);
        JLabel valueComponent = UiKit.valueLabel(value);
        valueComponent.setFont(valueComponent.getFont().deriveFont(Font.BOLD, 24f));
        JLabel captionComponent = UiKit.muted(caption);

        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new javax.swing.BoxLayout(
                copy,
                javax.swing.BoxLayout.Y_AXIS
        ));
        copy.add(labelComponent);
        copy.add(valueComponent);
        copy.add(captionComponent);
        add(copy, BorderLayout.CENTER);
    }
}
