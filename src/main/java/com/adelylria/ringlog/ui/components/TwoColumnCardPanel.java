package com.adelylria.ringlog.ui.components;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

import javax.swing.JPanel;

public final class TwoColumnCardPanel extends JPanel {

    public TwoColumnCardPanel(int gap) {
        super(new NaturalCardLayout(gap));
        setOpaque(false);
    }

    private static final class NaturalCardLayout implements LayoutManager {

        private static final int COLUMNS = 2;
        private final int gap;

        private NaturalCardLayout(int gap) {
            this.gap = gap;
        }

        @Override
        public void addLayoutComponent(String name, Component component) {
        }

        @Override
        public void removeLayoutComponent(Component component) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            return measure(parent, true);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return measure(parent, false);
        }

        @Override
        public void layoutContainer(Container parent) {
            synchronized (parent.getTreeLock()) {
                Insets insets = parent.getInsets();
                int availableWidth = Math.max(
                        0,
                        parent.getWidth() - insets.left - insets.right
                );
                int columnWidth = Math.max(0, (availableWidth - gap) / COLUMNS);
                int y = insets.top;
                int count = parent.getComponentCount();

                for (int index = 0; index < count; index += COLUMNS) {
                    int rowHeight = rowHeight(parent, index, true);
                    for (int column = 0; column < COLUMNS; column++) {
                        int childIndex = index + column;
                        if (childIndex < count) {
                            int x = insets.left + column * (columnWidth + gap);
                            parent.getComponent(childIndex).setBounds(
                                    x,
                                    y,
                                    columnWidth,
                                    rowHeight
                            );
                        }
                    }
                    y += rowHeight + gap;
                }
            }
        }

        private Dimension measure(Container parent, boolean preferred) {
            Insets insets = parent.getInsets();
            int columnWidth = 0;
            int height = 0;
            int count = parent.getComponentCount();

            for (int index = 0; index < count; index += COLUMNS) {
                height += rowHeight(parent, index, preferred);
                if (index + COLUMNS < count) {
                    height += gap;
                }
                for (int column = 0; column < COLUMNS; column++) {
                    int childIndex = index + column;
                    if (childIndex < count) {
                        Dimension size = preferred
                                ? parent.getComponent(childIndex).getPreferredSize()
                                : parent.getComponent(childIndex).getMinimumSize();
                        columnWidth = Math.max(columnWidth, size.width);
                    }
                }
            }

            return new Dimension(
                    insets.left + insets.right + columnWidth * COLUMNS + gap,
                    insets.top + insets.bottom + height
            );
        }

        private int rowHeight(Container parent, int index, boolean preferred) {
            int height = 0;
            for (int column = 0; column < COLUMNS; column++) {
                int childIndex = index + column;
                if (childIndex < parent.getComponentCount()) {
                    Dimension size = preferred
                            ? parent.getComponent(childIndex).getPreferredSize()
                            : parent.getComponent(childIndex).getMinimumSize();
                    height = Math.max(height, size.height);
                }
            }
            return height;
        }
    }
}
