package com.adelylria.ringlog.ui.theme;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.RenderingHints;

import javax.swing.JPanel;

public class SurfacePanel extends JPanel {

    private boolean hovered;

    public SurfacePanel(LayoutManager layout) {
        super(layout);
        setOpaque(false);
        setBorder(UiKit.cardBorder());
    }

    public void setHovered(boolean hovered) {
        if (this.hovered == hovered) {
            return;
        }
        this.hovered = hovered;
        repaint();
    }

    @Override
    public Dimension getMaximumSize() {
        Dimension preferred = getPreferredSize();
        return new Dimension(Integer.MAX_VALUE, preferred.height);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );
            Color color = hovered
                    ? UiKit.surfaceRaisedColor()
                    : UiKit.surfaceColor();
            g2.setColor(color);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
        } finally {
            g2.dispose();
        }
        super.paintComponent(graphics);
    }
}
