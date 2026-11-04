package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.view.BirdEventTimelineItem;
import com.adelylria.ringlog.ui.components.BirdEventCard;
import com.adelylria.ringlog.ui.components.TimelineView;

public final class TimelinePhotoLoadingTest {

    private TimelinePhotoLoadingTest() { }

    public static void onlyVisibleRowsLoadAndChangedFilesInvalidateTheThumbnail() throws Exception {
        Path root = Files.createTempDirectory("ringlog-timeline-photos-");
        try {
            Path photo = root.resolve("sample.png");
            writePhoto(photo, 0xffd02020);
            List<BirdEventTimelineItem> items = items(100, photo);
            TimelineView[] timeline = new TimelineView[1];
            List<JLabel> labels = new ArrayList<>();
            Consumer<Long> onOpen = ignored -> { };
            SwingUtilities.invokeAndWait(() -> {
                timeline[0] = new TimelineView();
                timeline[0].setItems(items, onOpen, 100);
                labels.addAll(photoLabels(timeline[0]));
                require(labels.size() == 100, "Every photo row needs its lazy thumbnail slot");
                require(labels.stream().allMatch(label -> "idle".equals(label.getClientProperty("photoState"))),
                        "Creating off-screen rows must not start image decoding");
                paintViewport(timeline[0]);
                long requested = labels.stream()
                        .filter(label -> !"idle".equals(label.getClientProperty("photoState"))).count();
                require(requested > 0 && requested < 10,
                        "Only the rows intersecting the viewport should request photographs");
            });
            await(() -> "loaded".equals(labels.get(0).getClientProperty("photoState")),
                    "The visible row must eventually show its thumbnail");
            SwingUtilities.invokeAndWait(() -> {
                ImageIcon icon = (ImageIcon) labels.get(0).getIcon();
                require(icon.getIconWidth() <= 92 && icon.getIconHeight() <= 92,
                        "Timeline previews must stay bounded to thumbnail dimensions");
                BufferedImage image = (BufferedImage) icon.getImage();
                int color = image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
                require((color >> 16 & 255) > (color & 255), "The first thumbnail must show the red photo");
                timeline[0].setItems(List.of(), onOpen, 100);
            });

            writePhoto(photo, 0xff2020d0);
            Files.setLastModifiedTime(photo, FileTime.fromMillis(System.currentTimeMillis() + 2000));
            JLabel[] changed = new JLabel[1];
            SwingUtilities.invokeAndWait(() -> {
                TimelineView replacement = new TimelineView();
                replacement.setItems(items(1, photo), onOpen, 1);
                changed[0] = photoLabels(replacement).get(0);
                paintViewport(replacement);
            });
            await(() -> "loaded".equals(changed[0].getClientProperty("photoState")),
                    "A changed photo must produce another thumbnail");
            SwingUtilities.invokeAndWait(() -> {
                BufferedImage image = (BufferedImage) ((ImageIcon) changed[0].getIcon()).getImage();
                int color = image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
                require((color & 255) > (color >> 16 & 255),
                        "The thumbnail cache must not return stale bytes after the file changes");
            });
        } finally {
            deleteTemporary(root);
        }
    }

    public static void missingAndDiscardedPhotosCannotBlockOrRepopulateTheTimeline() throws Exception {
        Path root = Files.createTempDirectory("ringlog-timeline-cancel-");
        try {
            Path missing = root.resolve("missing.png");
            JLabel[] absent = new JLabel[1];
            SwingUtilities.invokeAndWait(() -> {
                TimelineView timeline = new TimelineView();
                timeline.setItems(items(1, missing), ignored -> { }, 1);
                absent[0] = photoLabels(timeline).get(0);
                paintViewport(timeline);
            });
            await(() -> "missing".equals(absent[0].getClientProperty("photoState")),
                    "Unavailable photographs must leave their records readable");

            Path photo = root.resolve("discarded.png");
            writePhoto(photo, 0xff208040);
            JLabel[] discarded = new JLabel[1];
            TimelineView[] timeline = new TimelineView[1];
            SwingUtilities.invokeAndWait(() -> {
                timeline[0] = new TimelineView();
                Consumer<Long> onOpen = ignored -> { };
                timeline[0].setItems(items(1, photo), onOpen, 1);
                discarded[0] = photoLabels(timeline[0]).get(0);
                paintViewport(timeline[0]);
                timeline[0].setItems(List.of(), onOpen, 1);
            });
            for (int index = 0; index < 20; index++) {
                Thread.sleep(10);
                SwingUtilities.invokeAndWait(() -> {
                    require(discarded[0].getIcon() == null,
                            "Completion of a discarded photo request must not update its card");
                    require(descendants(timeline[0], BirdEventCard.class).isEmpty(),
                            "Late image completion must not restore rows removed by a filter");
                });
            }
        } finally {
            deleteTemporary(root);
        }
    }

    public static void paginationKeepsExistingCardsAndTheirOpenActions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<BirdEventTimelineItem> items = items(100, null);
            AtomicInteger opened = new AtomicInteger();
            Consumer<Long> onOpen = id -> opened.set(id.intValue());
            TimelineView timeline = new TimelineView();
            timeline.setItems(items, onOpen, 24);
            BirdEventCard first = descendants(timeline, BirdEventCard.class).get(0);
            timeline.setItems(items, onOpen, 100);
            List<BirdEventCard> expanded = descendants(timeline, BirdEventCard.class);
            require(expanded.size() == 100 && expanded.get(0) == first,
                    "Showing another page must preserve cards that are already visible");
            descendants(first, JButton.class).get(0).doClick();
            require(opened.get() == 1, "A retained card must still open the correct record");
        });
    }

    private static List<BirdEventTimelineItem> items(int count, Path photo) {
        List<BirdEventTimelineItem> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(new BirdEventTimelineItem(index + 1, index + 1, "SYNTH-" + index,
                    EventType.RINGING, "2026-11-04", "08:30", "Especie de prueba", "Lugar de prueba",
                    "Notas de prueba", "GOOD", photo == null ? null : photo.toString()));
        }
        return List.copyOf(result);
    }

    private static void paintViewport(TimelineView timeline) {
        timeline.setSize(900, timeline.getPreferredSize().height);
        layout(timeline);
        BufferedImage viewport = new BufferedImage(900, 500, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = viewport.createGraphics();
        graphics.setClip(0, 0, 900, 500);
        timeline.paint(graphics);
        graphics.dispose();
    }

    private static void layout(Component component) {
        if (component instanceof Container parent) {
            parent.doLayout();
            for (Component child : parent.getComponents()) layout(child);
        }
    }

    private static List<JLabel> photoLabels(Component component) {
        return descendants(component, JLabel.class).stream()
                .filter(label -> "eventCardPhoto".equals(label.getName())).toList();
    }

    private static <T extends Component> List<T> descendants(Component component, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(component)) result.add(type.cast(component));
        if (component instanceof Container parent) {
            for (Component child : parent.getComponents()) result.addAll(descendants(child, type));
        }
        return result;
    }

    private static void writePhoto(Path path, int color) throws Exception {
        BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new java.awt.Color(color));
        graphics.fillRect(0, 0, 400, 300);
        graphics.dispose();
        ImageIO.write(image, "png", path.toFile());
    }

    private static void await(java.util.function.BooleanSupplier condition, String message) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            boolean[] complete = new boolean[1];
            SwingUtilities.invokeAndWait(() -> complete[0] = condition.getAsBoolean());
            if (complete[0]) return;
            Thread.sleep(10);
        }
        throw new AssertionError(message);
    }

    private static void deleteTemporary(Path root) throws Exception {
        Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        require(root.toAbsolutePath().normalize().startsWith(temporary) && !root.equals(temporary),
                "Only the isolated temporary fixture may be removed");
        try (var files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        onlyVisibleRowsLoadAndChangedFilesInvalidateTheThumbnail();
        missingAndDiscardedPhotosCannotBlockOrRepopulateTheTimeline();
        paginationKeepsExistingCardsAndTheirOpenActions();
        System.out.println("TimelinePhotoLoadingTest: PASS");
    }
}
