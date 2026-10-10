package com.adelylria.ringlog.ui.components;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import javax.swing.JPanel;

/** A flow whose preferred height includes every wrapped row, also inside a scroll pane. */
public final class WrappingPanel extends JPanel {
    public WrappingPanel(int horizontalGap, int verticalGap) {
        setOpaque(false);
        setLayout(new LayoutManager() {
            public void addLayoutComponent(String name, Component component) { }
            public void removeLayoutComponent(Component component) { }
            public Dimension minimumLayoutSize(Container parent) { return new Dimension(0, preferredLayoutSize(parent).height); }
            public Dimension preferredLayoutSize(Container parent) { return arrange(parent, false); }
            public void layoutContainer(Container parent) { arrange(parent, true); }
            private Dimension arrange(Container parent, boolean layout) {
                Insets insets = parent.getInsets();
                int width = availableWidth(parent) - insets.left - insets.right;
                width = Math.max(1, width);
                int x=0, y=0, rowHeight=0, usedWidth=0;
                for (Component child : parent.getComponents()) {
                    if (!child.isVisible()) continue;
                    Dimension size = child.getPreferredSize();
                    int childWidth = Math.min(width, size.width);
                    if (x > 0 && x + childWidth > width) {
                        y += rowHeight + verticalGap;
                        x = 0; rowHeight = 0;
                    }
                    if (layout) child.setBounds(insets.left + x, insets.top + y, childWidth, size.height);
                    usedWidth = Math.max(usedWidth, x + childWidth);
                    x += childWidth + horizontalGap;
                    rowHeight = Math.max(rowHeight, size.height);
                }
                return new Dimension(usedWidth + insets.left + insets.right, y + rowHeight + insets.top + insets.bottom);
            }
        });
    }
    private static int availableWidth(Container parent) {
        for (Container candidate = parent; candidate != null; candidate = candidate.getParent()) {
            if (candidate.getWidth() > 0) return candidate.getWidth();
        }
        return Integer.MAX_VALUE / 2;
    }
    @Override public void setBounds(int x, int y, int width, int height) {
        boolean resized = width != getWidth();
        super.setBounds(x, y, width, height);
        if (resized) revalidate();
    }
}
