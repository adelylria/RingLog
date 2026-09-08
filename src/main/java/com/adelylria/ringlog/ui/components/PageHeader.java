package com.adelylria.ringlog.ui.components;

import java.awt.BorderLayout;
import java.awt.FlowLayout;

import javax.swing.JComponent;
import javax.swing.JPanel;

import com.adelylria.ringlog.ui.theme.UiKit;

public class PageHeader extends JPanel {

    public PageHeader(
            String eyebrow,
            String title,
            String subtitle,
            JComponent action
    ) {
        super(new BorderLayout(20, 0));
        setOpaque(false);

        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new javax.swing.BoxLayout(
                copy,
                javax.swing.BoxLayout.Y_AXIS
        ));
        copy.add(UiKit.eyebrow(eyebrow));
        copy.add(javax.swing.Box.createVerticalStrut(5));
        copy.add(UiKit.title(title));
        if (subtitle != null && !subtitle.isBlank()) {
            copy.add(javax.swing.Box.createVerticalStrut(5));
            copy.add(UiKit.subtitle(subtitle));
        }

        add(copy, BorderLayout.CENTER);
        if (action != null) {
            JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            actionPanel.setOpaque(false);
            actionPanel.add(action);
            add(actionPanel, BorderLayout.EAST);
        }
    }
}
