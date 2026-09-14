package com.adelylria.ringlog.ui.components;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;

import com.adelylria.ringlog.model.view.BirdEventTimelineItem;
import com.adelylria.ringlog.ui.theme.SurfacePanel;
import com.adelylria.ringlog.ui.theme.UiKit;

/** Compact, chronological navigation through every event belonging to one bird. */
public final class BirdHistoryPanel extends SurfacePanel {

    public static final String SELECTED_PROPERTY = "RingLog.historySelected";

    public BirdHistoryPanel(
            List<BirdEventTimelineItem> events,
            long selectedEventId,
            Consumer<Long> onSelect
    ) {
        super(new BorderLayout(0, 12));
        setName("birdHistoryPanel");
        List<BirdEventTimelineItem> ordered = events.stream()
                .sorted(Comparator
                        .comparing(
                                BirdEventTimelineItem::eventDate,
                                Comparator.nullsLast(Comparator.naturalOrder())
                        )
                        .thenComparing(
                                BirdEventTimelineItem::eventTime,
                                Comparator.nullsLast(Comparator.naturalOrder())
                        )
                        .thenComparingLong(BirdEventTimelineItem::id))
                .toList();

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        heading.add(UiKit.valueLabel("Historia de esta anilla"));
        heading.add(Box.createVerticalStrut(3));
        heading.add(UiKit.muted(historyDescription(ordered.size())));
        add(heading, BorderLayout.NORTH);

        JPanel track = new JPanel();
        track.setOpaque(false);
        track.setLayout(new BoxLayout(track, BoxLayout.X_AXIS));
        for (int index = 0; index < ordered.size(); index++) {
            BirdEventTimelineItem item = ordered.get(index);
            if (index > 0) {
                JLabel connector = UiKit.muted("→");
                connector.setBorder(BorderFactory.createEmptyBorder(0, 9, 0, 9));
                track.add(connector);
            }
            boolean selected = item.id() == selectedEventId;
            HistoryEventButton button = new HistoryEventButton(item, selected);
            button.setName("historyEvent-" + item.id());
            button.putClientProperty(SELECTED_PROPERTY, selected);
            if (!selected) {
                button.addActionListener(event -> onSelect.accept(item.id()));
            }
            track.add(button);
        }
        track.add(Box.createHorizontalGlue());

        JScrollPane scroll = new JScrollPane(
                track,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        );
        scroll.setName("birdHistoryScroll");
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setViewportBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getHorizontalScrollBar().setUnitIncrement(24);
        scroll.setPreferredSize(new Dimension(720, 104));
        add(scroll, BorderLayout.CENTER);
    }

    private static String historyDescription(int count) {
        String moments = count == 1 ? "1 momento guardado" : count + " momentos guardados";
        return moments + " · selecciona uno para consultar sus datos.";
    }

    private static String cardText(BirdEventTimelineItem item) {
        return "<html>"
                + escape(UiKit.date(item.eventDate()))
                + "<br><b>" + escape(item.eventType().toString()) + "</b>"
                + "<br>" + escape(UiKit.display(item.place()))
                + "</html>";
    }

    private static String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static final class HistoryEventButton extends JButton {

        private final boolean selectedEvent;

        private HistoryEventButton(BirdEventTimelineItem item, boolean selectedEvent) {
            super(cardText(item));
            this.selectedEvent = selectedEvent;
            setHorizontalAlignment(SwingConstants.LEFT);
            setVerticalAlignment(SwingConstants.CENTER);
            setMargin(new Insets(11, 14, 11, 14));
            setBorder(BorderFactory.createEmptyBorder(11, 14, 11, 14));
            setContentAreaFilled(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(218, 82));
            setMinimumSize(getPreferredSize());
            setMaximumSize(getPreferredSize());
            setToolTipText(selectedEvent
                    ? "Registro seleccionado"
                    : "Ver este registro de la anilla");
            getAccessibleContext().setAccessibleName(
                    item.eventType() + " · " + UiKit.date(item.eventDate())
                            + " · " + UiKit.display(item.place())
            );
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON
                );
                Color fill = selectedEvent
                        ? UiKit.accentSoftColor()
                        : getModel().isRollover()
                                ? UiKit.surfaceRaisedColor()
                                : UiKit.surfaceColor();
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
            } finally {
                g2.dispose();
            }
            setForeground(selectedEvent ? UiKit.accentColor() : UiKit.textColor());
            super.paintComponent(graphics);
        }

        @Override
        protected void paintBorder(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON
                );
                g2.setColor(selectedEvent || isFocusOwner()
                        ? UiKit.accentColor()
                        : UiKit.borderColor());
                g2.setStroke(new BasicStroke(selectedEvent ? 2f : 1f));
                g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 18, 18);
            } finally {
                g2.dispose();
            }
        }
    }
}
