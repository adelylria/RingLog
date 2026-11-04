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
    private List<BirdEventTimelineItem> renderedItems = List.of();
    private Consumer<Long> currentOnOpen;
    private String currentDate;

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
        int count = maximumItems > 0 ? Math.min(maximumItems, items.size()) : items.size();
        List<BirdEventTimelineItem> nextItems = List.copyOf(items.subList(0, count));
        boolean append = currentOnOpen == onOpen && !renderedItems.isEmpty()
                && nextItems.size() >= renderedItems.size()
                && nextItems.subList(0, renderedItems.size()).equals(renderedItems);
        if (append && nextItems.size() == renderedItems.size()) return;
        int firstNew = append ? renderedItems.size() : 0;
        if (!append) {
            for (Component component : content.getComponents()) {
                if (component instanceof BirdEventCard card) card.cancelPhotoLoading();
            }
            content.removeAll();
            currentDate = null;
        }

        if (items.isEmpty()) {
            content.add(new EmptyStatePanel(
                    emptyTitle,
                    emptyMessage,
                    null
            ));
        } else {
            for (int index = firstNew; index < nextItems.size(); index++) {
                BirdEventTimelineItem item = nextItems.get(index);

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
            }
        }

        renderedItems = nextItems;
        currentOnOpen = onOpen;

        content.revalidate();
        content.repaint();
    }

    public Dimension getPreferredContentSize() {
        return content.getPreferredSize();
    }
}
