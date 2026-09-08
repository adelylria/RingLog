package com.adelylria.ringlog.ui.theme;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

import javax.swing.border.AbstractBorder;

public final class RoundedBorder extends AbstractBorder {

    private final Color color;
    private final int radius;
    private final int thickness;
    private final Insets padding;

    public RoundedBorder(
            Color color,
            int radius,
            int thickness,
            Insets padding
    ) {
        this.color = color;
        this.radius = radius;
        this.thickness = thickness;
        this.padding = (Insets) padding.clone();
    }

    @Override
    public Insets getBorderInsets(Component component) {
        return new Insets(
                padding.top + thickness,
                padding.left + thickness,
                padding.bottom + thickness,
                padding.right + thickness
        );
    }

    @Override
    public Insets getBorderInsets(Component component, Insets insets) {
        Insets result = getBorderInsets(component);
        insets.top = result.top;
        insets.left = result.left;
        insets.bottom = result.bottom;
        insets.right = result.right;
        return insets;
    }

    @Override
    public void paintBorder(
            Component component,
            Graphics graphics,
            int x,
            int y,
            int width,
            int height
    ) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );
            g2.setColor(color == null ? UiKit.borderColor() : color);
            float half = thickness / 2f;
            g2.setStroke(new java.awt.BasicStroke(thickness));
            g2.draw(new RoundRectangle2D.Float(
                    x + half,
                    y + half,
                    Math.max(0f, width - thickness),
                    Math.max(0f, height - thickness),
                    radius,
                    radius
            ));
        } finally {
            g2.dispose();
        }
    }
}
