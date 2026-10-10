package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.view.BirdEventDetail;
import com.adelylria.ringlog.model.view.BirdEventTimelineItem;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.ui.components.EventPhotoGallery;
import com.adelylria.ringlog.ui.panels.CaptureDetailPanel;

public final class EventPhotoViewerTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("ringlog-photo-viewer-");
        try {
            Path first = root.resolve("first.png");
            Path second = root.resolve("second.png");
            ImageIO.write(new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB), "png", first.toFile());
            ImageIO.write(new BufferedImage(200, 300, BufferedImage.TYPE_INT_RGB), "png", second.toFile());
            var detail = new BirdEventDetail(
                    1, 7, "TEST-1", "TEST SPECIES", EventType.RINGING,
                    "2026-01-01", "10:30", "Lugar de prueba", null, null, null,
                    "U", "4", null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null,
                    null, null, null, null, null, "Notas", false,
                    List.of(first.toString(), second.toString()));
            AtomicInteger reads = new AtomicInteger();
            BirdEventRepository repository = new BirdEventRepository("unused.db") {
                @Override public Optional<BirdEventDetail> findDetail(long id) {
                    reads.incrementAndGet();
                    return Optional.of(detail);
                }
                @Override public List<BirdEventTimelineItem> findEventsByBird(long id) { return List.of(); }
            };
            // A read-only detail must offer the same viewer, without acquiring mutation services.
            CaptureDetailPanel[] holder = new CaptureDetailPanel[1];
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new CaptureDetailPanel(repository, () -> { }, ignored -> { }, false,
                        root.resolve("map-cache"));
                holder[0].setEventId(1);
            });
            CaptureDetailPanel panel = holder[0];
            await(() -> loaded(panel), "The record thumbnail should load");
            JScrollPane[] scroll = new JScrollPane[1];
            Point[] position = new Point[1];
            SwingUtilities.invokeAndWait(() -> {
                panel.setSize(960, 760);
                scroll[0] = find(panel, "captureDetailScroll", JScrollPane.class);
                for (int i = 0; i < 4; i++) {
                    layout(panel);
                    Component view = scroll[0].getViewport().getView();
                    view.setSize(scroll[0].getViewport().getWidth(), view.getPreferredSize().height);
                    layout((Container) view);
                }
                scroll[0].getViewport().setViewPosition(new Point(0, 320));
                position[0] = scroll[0].getViewport().getViewPosition();
                clickPhoto(panel);
                require(!scroll[0].isVisible(), "Enlarging must hide the record, not open a dialog");
            });
            await(() -> loaded(find(panel, "eventPhotoViewer", JPanel.class)), "The enlarged photo should load");
            SwingUtilities.invokeAndWait(() -> {
                JPanel viewer = find(panel, "eventPhotoViewer", JPanel.class);
                require(viewer.isVisible(), "The viewer must live inside the record panel");
                require(find(viewer, "addEventPhoto", JButton.class) == null,
                        "Enlarging in portable mode must not expose mutation actions");
                find(viewer, "nextEventPhoto", JButton.class).doClick();
                require(find(viewer, "eventPhotoGallery", EventPhotoGallery.class).selectedIndex() == 1,
                        "The viewer should navigate this record's images");
                find(viewer, "backToPhotoRecord", JButton.class).doClick();
            });
            await(() -> scroll[0].isVisible() && position[0].equals(scroll[0].getViewport().getViewPosition()),
                    "Returning must restore the previous scroll position");
            await(() -> loaded(panel), "The selected thumbnail should load after returning");
            SwingUtilities.invokeAndWait(() -> {
                require(find(panel, "eventPhotoViewer", JPanel.class) == null,
                        "Closing must release the viewer");
                require(find(panel, "eventPhotoGallery", EventPhotoGallery.class).selectedIndex() == 1,
                        "Returning must retain the last viewed photo");
                require(reads.get() == 1, "Viewing photos must not reload or modify the record");
                clickPhoto(panel);
                JPanel viewer = find(panel, "eventPhotoViewer", JPanel.class);
                require(find(viewer, "eventPhotoGallery", EventPhotoGallery.class).selectedIndex() == 1,
                        "Reopening must enlarge the current photo, not reset to the first");
                viewer.getActionMap().get("backToRecord").actionPerformed(null);
                require(scroll[0].isVisible(), "Escape should return to the record");
                clickPhoto(panel);
                panel.setEventId(2);
                require(find(panel, "eventPhotoViewer", JPanel.class) == null,
                        "Navigating to another record must discard the old photo viewer");
            });
            await(() -> loaded(panel), "The next record must load normally");
            SwingUtilities.invokeAndWait(() -> find(panel, "eventPhotoGallery", EventPhotoGallery.class).dispose());
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
        System.out.println("EventPhotoViewerTest: PASS");
    }

    private static boolean loaded(Component root) {
        JPanel canvas = root == null ? null : find(root, "eventPhotoPreview", JPanel.class);
        return canvas != null && "loaded".equals(canvas.getClientProperty("photoState"));
    }

    private static void clickPhoto(Component root) {
        JPanel canvas = find(root, "eventPhotoPreview", JPanel.class);
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_CLICKED, 0, 0,
                150, 100, 1, false, MouseEvent.BUTTON1));
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        for (int i = 0; i < 150; i++) {
            boolean[] result = {false};
            SwingUtilities.invokeAndWait(() -> result[0] = condition.getAsBoolean());
            if (result[0]) return;
            Thread.sleep(20);
        }
        throw new AssertionError(message);
    }

    private static <T extends Component> T find(Component root, String name, Class<T> type) {
        if (name.equals(root.getName()) && type.isInstance(root)) return type.cast(root);
        if (root instanceof Container parent) for (Component child : parent.getComponents()) {
            T found = find(child, name, type);
            if (found != null) return found;
        }
        return null;
    }

    private static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) if (child instanceof Container c) layout(c);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
