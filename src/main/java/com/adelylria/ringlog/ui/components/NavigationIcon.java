package com.adelylria.ringlog.ui.components;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

import javax.swing.Icon;

public final class NavigationIcon implements Icon {

    public enum Kind {
        HOME,
        ADD,
        CAPTURES,
        SPECIES,
        PLACES,
        REPORTS,
        REVIEWS,
        EDIT,
        SETTINGS
    }

    private static final int SIZE = 18;
    private final Kind kind;

    public NavigationIcon(Kind kind) {
        this.kind = kind;
    }

    @Override
    public int getIconWidth() {
        return SIZE;
    }

    @Override
    public int getIconHeight() {
        return SIZE;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.translate(x, y);
            g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );
            g2.setColor(component.getForeground() == null
                    ? Color.DARK_GRAY
                    : component.getForeground());
            g2.setStroke(new BasicStroke(
                    1.6f,
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND
            ));

            switch (kind) {
                case HOME -> paintHome(g2);
                case ADD -> paintAdd(g2);
                case CAPTURES -> paintCaptures(g2);
                case SPECIES -> paintSpecies(g2);
                case PLACES -> paintPlace(g2);
                case REPORTS -> paintReports(g2);
                case REVIEWS -> paintReviews(g2);
                case EDIT -> paintEdit(g2);
                case SETTINGS -> paintSettings(g2);
            }
        } finally {
            g2.dispose();
        }
    }

    private static void paintHome(Graphics2D g2) {
        Path2D roof = new Path2D.Double();
        roof.moveTo(2.5, 8.5);
        roof.lineTo(9, 3);
        roof.lineTo(15.5, 8.5);
        g2.draw(roof);
        g2.draw(new Rectangle2D.Double(4.5, 8, 9, 7));
        g2.draw(new Line2D.Double(8, 15, 8, 11));
    }

    private static void paintAdd(Graphics2D g2) {
        g2.draw(new Ellipse2D.Double(2.5, 2.5, 13, 13));
        g2.draw(new Line2D.Double(9, 5.5, 9, 12.5));
        g2.draw(new Line2D.Double(5.5, 9, 12.5, 9));
    }

    private static void paintCaptures(Graphics2D g2) {
        g2.draw(new Ellipse2D.Double(3, 3, 12, 12));
        g2.draw(new Ellipse2D.Double(6.5, 6.5, 5, 5));
        g2.draw(new Line2D.Double(9, 1.5, 9, 4));
        g2.draw(new Line2D.Double(9, 14, 9, 16.5));
    }

    private static void paintSpecies(Graphics2D g2) {
        Path2D leaf = new Path2D.Double();
        leaf.moveTo(3, 14.5);
        leaf.curveTo(3.5, 6, 8, 2.5, 15, 3);
        leaf.curveTo(15, 10, 10.5, 14.5, 3, 14.5);
        g2.draw(leaf);
        g2.draw(new Line2D.Double(4, 13.5, 12.5, 5));
    }

    private static void paintPlace(Graphics2D g2) {
        Path2D pin = new Path2D.Double();
        pin.moveTo(9, 16);
        pin.curveTo(7, 13, 3.5, 9.5, 3.5, 6.5);
        pin.curveTo(3.5, 3.5, 6, 1.5, 9, 1.5);
        pin.curveTo(12, 1.5, 14.5, 3.5, 14.5, 6.5);
        pin.curveTo(14.5, 9.5, 11, 13, 9, 16);
        g2.draw(pin);
        g2.draw(new Ellipse2D.Double(7, 4.5, 4, 4));
    }

    private static void paintReports(Graphics2D g2) {
        g2.draw(new Rectangle2D.Double(2.5, 9.5, 3, 6));
        g2.draw(new Rectangle2D.Double(7.5, 5.5, 3, 10));
        g2.draw(new Rectangle2D.Double(12.5, 2.5, 3, 13));
    }

    private static void paintReviews(Graphics2D g2) {
        Path2D bubble = new Path2D.Double();
        bubble.moveTo(3, 3);
        bubble.lineTo(15, 3);
        bubble.lineTo(15, 12.5);
        bubble.lineTo(9, 12.5);
        bubble.lineTo(5.5, 15.5);
        bubble.lineTo(5.5, 12.5);
        bubble.lineTo(3, 12.5);
        bubble.closePath();
        g2.draw(bubble);
        g2.draw(new Line2D.Double(9, 5.5, 9, 9));
        g2.draw(new Ellipse2D.Double(8.5, 10.5, 1, 1));
    }

    private static void paintEdit(Graphics2D g2) {
        Path2D pencil = new Path2D.Double();
        pencil.moveTo(3, 14.5);
        pencil.lineTo(4.2, 10.5);
        pencil.lineTo(12.3, 2.4);
        pencil.lineTo(15.6, 5.7);
        pencil.lineTo(7.5, 13.8);
        pencil.closePath();
        g2.draw(pencil);
        g2.draw(new Line2D.Double(10.7, 4, 14, 7.3));
        g2.draw(new Line2D.Double(3, 15.5, 7.4, 13.8));
    }

    private static void paintSettings(Graphics2D g2) {
        g2.draw(new Ellipse2D.Double(3.5, 3.5, 11, 11));
        g2.draw(new Ellipse2D.Double(7, 7, 4, 4));
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * i / 4;
            double x1 = 9 + Math.cos(angle) * 6;
            double y1 = 9 + Math.sin(angle) * 6;
            double x2 = 9 + Math.cos(angle) * 8;
            double y2 = 9 + Math.sin(angle) * 8;
            g2.draw(new Line2D.Double(x1, y1, x2, y2));
        }
    }
}
