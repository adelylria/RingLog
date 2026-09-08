package com.adelylria.ringlog.ui.theme;

import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.JLabel;
import javax.swing.border.EmptyBorder;

final class PillLabel extends JLabel {

    PillLabel(String text) {
        super(text);
        setBorder(new EmptyBorder(5, 10, 5, 10));
        setForeground(UiKit.accentColor());
        setFont(getFont().deriveFont(Font.BOLD, 12f));
        setOpaque(false);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        setForeground(UiKit.accentColor());
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );
            g2.setColor(UiKit.accentSoftColor());
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
        } finally {
            g2.dispose();
        }
        super.paintComponent(graphics);
    }
}
