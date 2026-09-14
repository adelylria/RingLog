package com.adelylria.ringlog.ui.components;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Image;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.view.BirdEventTimelineItem;
import com.adelylria.ringlog.ui.theme.SurfacePanel;
import com.adelylria.ringlog.ui.theme.UiKit;

public class BirdEventCard extends SurfacePanel {

    public BirdEventCard(
            BirdEventTimelineItem item,
            Consumer<Long> onOpen
    ) {
        super(new BorderLayout(16, 0));
        List<EventType> historyTypes = item.historyEventTypes().stream()
                .distinct()
                .toList();
        boolean hasJourney = historyTypes.size() > 1;
        String journey = hasJourney ? journeyText(historyTypes) : null;
        setBorder(UiKit.cardBorder());
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 164));
        getAccessibleContext().setAccessibleName(
                item.eventType() + " · " + item.species() + " · " + item.ringNumber()
                        + (hasJourney ? " · " + journey : "")
        );

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new javax.swing.BoxLayout(text, javax.swing.BoxLayout.Y_AXIS));

        JPanel metadata = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        metadata.setOpaque(false);
        metadata.setAlignmentX(Component.LEFT_ALIGNMENT);
        metadata.add(UiKit.chip(UiKit.time(item.eventTime())));
        metadata.add(UiKit.muted("Anilla " + UiKit.display(item.ringNumber())));
        if (hasJourney) {
            addJourneyChips(metadata, historyTypes);
        } else {
            metadata.add(UiKit.chip(item.eventType().toString()));
        }
        if ("REVIEW".equalsIgnoreCase(item.reviewStatus())) {
            JLabel review = UiKit.chip("Revisar");
            review.setToolTipText("Este registro tiene datos pendientes de comprobar");
            metadata.add(review);
        }
        text.add(metadata);
        text.add(javax.swing.Box.createVerticalStrut(8));

        JLabel species = UiKit.valueLabel(item.species());
        species.setFont(species.getFont().deriveFont(Font.BOLD, 20f));
        species.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(species);
        text.add(javax.swing.Box.createVerticalStrut(4));
        JLabel place = UiKit.muted("Lugar · " + UiKit.display(item.place()));
        place.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(place);
        text.add(javax.swing.Box.createVerticalStrut(7));
        JLabel observations = UiKit.muted(summarize(item.observations()));
        observations.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(observations);
        add(text, BorderLayout.CENTER);

        JPanel trailing = new JPanel(new BorderLayout(0, 8));
        trailing.setOpaque(false);
        if (hasText(item.birdCondition())) {
            trailing.add(
                    UiKit.chip(UiKit.friendlyCode(item.birdCondition())),
                    BorderLayout.NORTH
            );
        }

        JButton open = UiKit.secondaryButton("Abrir");
        open.setMargin(new Insets(7, 12, 7, 12));
        open.setToolTipText("Abrir el detalle del registro");
        open.addActionListener(event -> onOpen.accept(item.id()));
        open.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        trailing.add(open, BorderLayout.SOUTH);
        add(trailing, BorderLayout.EAST);

        MouseAdapter hover = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                setHovered(true);
            }

            @Override
            public void mouseExited(MouseEvent event) {
                setHovered(false);
            }
        };
        addMouseListener(hover);
        open.addMouseListener(hover);

        addPhoto(item.photoPath());
    }

    private void addPhoto(String photoPath) {
        if (photoPath == null || !Files.isRegularFile(Path.of(photoPath))) {
            return;
        }
        try {
            var source = ImageIO.read(Path.of(photoPath).toFile());
            if (source == null) {
                return;
            }
            int maximum = 92;
            double ratio = Math.min(
                    (double) maximum / source.getWidth(),
                    (double) maximum / source.getHeight()
            );
            int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
            int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
            Image scaled = source.getScaledInstance(width, height, Image.SCALE_SMOOTH);
            JLabel photo = new JLabel(new ImageIcon(scaled));
            photo.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 2));
            add(photo, BorderLayout.WEST);
        } catch (IOException ignored) {
            // The event remains readable when a referenced image is unavailable.
        }
    }

    private static String summarize(String observations) {
        if (!hasText(observations)) {
            return "Sin notas de campo";
        }
        String text = UiKit.display(observations).replaceAll("\\s+", " ").trim();
        return text.length() > 118 ? text.substring(0, 115) + "…" : text;
    }

    private static String journeyText(List<EventType> historyTypes) {
        return historyTypes.stream()
                .map(EventType::toString)
                .collect(java.util.stream.Collectors.joining(" → "));
    }

    private static void addJourneyChips(
            JPanel metadata,
            List<EventType> historyTypes
    ) {
        for (int index = 0; index < historyTypes.size(); index++) {
            if (index > 0) {
                metadata.add(UiKit.muted("→"));
            }
            EventType type = historyTypes.get(index);
            JLabel chip = UiKit.chip(type.toString());
            chip.setName("birdJourney-" + type.databaseValue());
            chip.setToolTipText("Fase registrada: " + type);
            metadata.add(chip);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
