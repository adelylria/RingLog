package com.adelylria.ringlog.ui.components;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;

import com.adelylria.ringlog.model.view.BirdEventTimelineItem;
import com.adelylria.ringlog.ui.theme.UiKit;

public class TimelineView extends JPanel {

    private final JPanel content;

    public TimelineView() {
        super(new BorderLayout());
        setOpaque(false);

        content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        add(content, BorderLayout.CENTER);
    }

    public void setItems(
            List<BirdEventTimelineItem> items,
            Consumer<Long> onOpen,
            int maximumItems
    ) {
        setItems(
                items,
                onOpen,
                maximumItems,
                "Todavía no hay entradas",
                "Cuando guardes un registro aparecerá aquí como una página de tu diario."
        );
    }

    public void setItems(
            List<BirdEventTimelineItem> items,
            Consumer<Long> onOpen,
            int maximumItems,
            String emptyTitle,
            String emptyMessage
    ) {
        content.removeAll();

        if (items.isEmpty()) {
            content.add(new EmptyStatePanel(
                    emptyTitle,
                    emptyMessage,
                    null
            ));
        } else {
            String currentDate = null;
            int rendered = 0;

            for (BirdEventTimelineItem item : items) {
                if (maximumItems > 0 && rendered >= maximumItems) {
                    break;
                }

                if (!item.eventDate().equals(currentDate)) {
                    if (currentDate != null) {
                        content.add(Box.createVerticalStrut(18));
                    }
                    JPanel dateRow = new JPanel(new BorderLayout(12, 0));
                    dateRow.setOpaque(false);
                    dateRow.setAlignmentX(Component.LEFT_ALIGNMENT);
                    dateRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
                    JLabel date = UiKit.eyebrow(UiKit.date(item.eventDate()));
                    dateRow.add(date, BorderLayout.WEST);
                    JSeparator separator = UiKit.separator();
                    separator.setForeground(UiKit.borderColor());
                    dateRow.add(separator, BorderLayout.CENTER);
                    content.add(dateRow);
                    content.add(Box.createVerticalStrut(9));
                    currentDate = item.eventDate();
                }

                BirdEventCard card = new BirdEventCard(item, onOpen);
                card.setAlignmentX(Component.LEFT_ALIGNMENT);
                content.add(card);
                content.add(Box.createVerticalStrut(12));
                rendered++;
            }
        }

        content.revalidate();
        content.repaint();
    }

    public Dimension getPreferredContentSize() {
        return content.getPreferredSize();
    }
}
