package com.adelylria.ringlog.ui.components;

import com.adelylria.ringlog.ui.theme.UiKit;
import com.adelylria.ringlog.ui.theme.SurfacePanel;

import java.awt.BorderLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

public class EmptyStatePanel extends SurfacePanel {

    public EmptyStatePanel(String title, String description, Runnable action) {
        super(new BorderLayout(0, 10));
        setBorder(UiKit.cardBorder());

        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new javax.swing.BoxLayout(
                copy,
                javax.swing.BoxLayout.Y_AXIS
        ));
        JLabel titleLabel = UiKit.valueLabel(title);
        JLabel descriptionLabel = UiKit.muted(description);
        copy.add(titleLabel);
        copy.add(descriptionLabel);
        add(copy, BorderLayout.CENTER);

        if (action != null) {
            JButton button = UiKit.secondaryButton("Crear un registro");
            button.addActionListener(event -> action.run());
            add(button, BorderLayout.EAST);
        }
    }
}
