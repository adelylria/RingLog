package com.adelylria.ringlog.ui.components;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.media.PhotoImages;
import com.adelylria.ringlog.model.view.BirdEventTimelineItem;
import com.adelylria.ringlog.ui.theme.SurfacePanel;
import com.adelylria.ringlog.ui.theme.UiKit;

public class BirdEventCard extends SurfacePanel {

    private PhotoThumbnail thumbnail;

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
        if (photoPath == null || photoPath.isBlank()) return;
        thumbnail = new PhotoThumbnail(photoPath);
        thumbnail.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 2));
        add(thumbnail, BorderLayout.WEST);
    }

    void cancelPhotoLoading() {
        if (thumbnail != null) thumbnail.cancelLoading();
    }

    @Override
    public void removeNotify() {
        cancelPhotoLoading();
        super.removeNotify();
    }

    /** Only painted cards request an image; off-screen rows do not queue file reads. */
    private static final class PhotoThumbnail extends JLabel {
        private static final int SIZE = 92;
        private static final int CACHE_SIZE = 128;
        private static final Map<PhotoKey, BufferedImage> CACHE = new LinkedHashMap<>(
                CACHE_SIZE, 0.75f, true);
        private static final ThreadPoolExecutor LOADER = new ThreadPoolExecutor(
                2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(48), task -> {
                    Thread thread = new Thread(task, "ringlog-timeline-photo");
                    thread.setDaemon(true);
                    return thread;
                });

        private final String file;
        private FutureTask<Void> task;
        private long generation;
        private boolean finished;

        private PhotoThumbnail(String file) {
            this.file = file;
            setName("eventCardPhoto");
            setPreferredSize(new Dimension(SIZE, SIZE));
            getAccessibleContext().setAccessibleName("Fotografía del registro");
            putClientProperty("photoState", "idle");
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (!finished && task == null) load();
            super.paintComponent(graphics);
        }

        private void load() {
            long request = ++generation;
            putClientProperty("photoState", "loading");
            task = new FutureTask<>(() -> {
                BufferedImage image = null;
                try {
                    image = readThumbnail(file);
                } catch (IOException | RuntimeException ignored) {
                    // A missing or damaged image must not prevent reading the event.
                }
                if (Thread.currentThread().isInterrupted()) return null;
                BufferedImage loaded = image;
                SwingUtilities.invokeLater(() -> {
                    if (request != generation) return;
                    task = null;
                    finished = true;
                    putClientProperty("photoState", loaded == null ? "missing" : "loaded");
                    if (loaded == null) {
                        java.awt.Container parent = getParent();
                        if (parent != null) {
                            parent.remove(this);
                            parent.revalidate();
                            parent.repaint();
                        }
                    } else {
                        setIcon(new ImageIcon(loaded));
                        setPreferredSize(new Dimension(loaded.getWidth() + 2, loaded.getHeight()));
                        revalidate();
                        repaint();
                    }
                });
                return null;
            });
            try {
                LOADER.execute(task);
            } catch (RejectedExecutionException busy) {
                task = null;
                Timer retry = new Timer(150, event -> repaint());
                retry.setRepeats(false);
                retry.start();
            }
        }

        private void cancelLoading() {
            generation++;
            if (task != null) {
                task.cancel(true);
                LOADER.remove(task);
                task = null;
            }
        }

        private static BufferedImage readThumbnail(String file) throws IOException {
            Path path = Path.of(file).toAbsolutePath().normalize();
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) throw new IOException("No es una fotografía.");
            PhotoKey key = new PhotoKey(path, attributes.lastModifiedTime(), attributes.size());
            synchronized (CACHE) {
                BufferedImage cached = CACHE.get(key);
                if (cached != null) return cached;
            }
            if (Thread.currentThread().isInterrupted()) throw new IOException("Carga cancelada.");
            BufferedImage source = PhotoImages.load(path, SIZE * 2, SIZE * 2).image();
            double ratio = Math.min((double) SIZE / source.getWidth(), (double) SIZE / source.getHeight());
            int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
            int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
            BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(source, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
            if (!Thread.currentThread().isInterrupted()) synchronized (CACHE) {
                CACHE.put(key, scaled);
                while (CACHE.size() > CACHE_SIZE) CACHE.remove(CACHE.keySet().iterator().next());
            }
            return scaled;
        }

        private record PhotoKey(Path file, FileTime modified, long size) { }
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
