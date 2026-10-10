package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Color;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import com.adelylria.ringlog.media.PhotoImages;
import com.adelylria.ringlog.ui.components.EventPhotoGallery;

public final class EventPhotoGalleryTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("ringlog-photo-gallery-");
        try {
            Path portrait = root.resolve("portrait.png");
            Path landscape = root.resolve("landscape.png");
            BufferedImage source = new BufferedImage(200, 600, BufferedImage.TYPE_INT_RGB);
            var graphics = source.createGraphics();
            graphics.setColor(Color.MAGENTA);
            graphics.fillRect(0, 0, 200, 600);
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, 200, 20);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 580, 200, 20);
            graphics.dispose();
            ImageIO.write(source, "png", portrait.toFile());
            ImageIO.write(new BufferedImage(1200, 300, BufferedImage.TYPE_INT_RGB), "png", landscape.toFile());
            var preview = PhotoImages.load(landscape, 400, 200).image();
            require(preview.getWidth() <= 400 && preview.getHeight() <= 200,
                    "Image decoding must be bounded before a preview is painted");
            AtomicInteger addRequests = new AtomicInteger();
            AtomicInteger openedPhoto = new AtomicInteger(-1);
            EventPhotoGallery[] holder = new EventPhotoGallery[1];
            SwingUtilities.invokeAndWait(() -> {
                var empty = new EventPhotoGallery(List.of(), addRequests::incrementAndGet);
                require(find(empty, "photoCounter", JLabel.class).getText().equals("Sin fotografías"),
                        "An empty record needs an explicit photo state");
                JButton add = find(empty, "addEventPhoto", JButton.class);
                require(add.getText().isEmpty() && add.getIcon() != null,
                        "The add action should be a compact icon, not a large text button");
                require(add.getPreferredSize().width <= 36 && add.getPreferredSize().height <= 36,
                        "The add action should not take another row below the photo");
                require("Añadir foto".equals(add.getAccessibleContext().getAccessibleName())
                                && add.getToolTipText().contains("Añadir"),
                        "An icon-only action still needs a tooltip and an accessible name");
                JPanel canvas = find(empty, "eventPhotoPreview", JPanel.class);
                require(SwingUtilities.isDescendingFrom(add, canvas),
                        "The add icon should sit inside the photo preview");
                empty.setSize(300, empty.getPreferredSize().height);
                layout(empty);
                var position = SwingUtilities.convertPoint(add.getParent(), add.getLocation(), canvas);
                require(position.x > canvas.getWidth() / 2 && position.y < 20
                                && position.x + add.getWidth() <= canvas.getWidth(),
                        "The icon should remain in the top-right corner at narrower widths");
                JPanel footer = find(empty, "photoGalleryFooter", JPanel.class);
                require(!footer.isVisible(), "An empty gallery should not reserve a footer row");
                empty.setStatus("No se pudo guardar la fotografía.");
                require(footer.isVisible() && find(empty, "photoSaveStatus", JTextArea.class).isVisible(),
                        "Compact controls must not hide save errors");
                empty.setStatus(null);
                require(!footer.isVisible(), "Clearing a status should reclaim the footer space");
                add.doClick();
                require(addRequests.get() == 1, "Adding a photo must be available inside the bird card");
                empty.setMutationActionsEnabled(false);
                require(!find(empty, "addEventPhoto", JButton.class).isEnabled(),
                        "A consistent portable snapshot must disable the add action");
                var readOnly = new EventPhotoGallery(List.of(portrait.toString()), null);
                require(find(readOnly, "addEventPhoto", JButton.class) == null,
                        "Portable galleries must not offer mutation actions");
                require(!find(readOnly, "photoGalleryFooter", JPanel.class).isVisible(),
                        "One photo needs no navigation row");
                require(!find(readOnly, "previousEventPhoto", JButton.class).isVisible()
                                && !find(readOnly, "nextEventPhoto", JButton.class).isVisible()
                                && !find(readOnly, "photoCounter", JLabel.class).isVisible(),
                        "A single photo must have no arrows or counter");
                holder[0] = new EventPhotoGallery(List.of(portrait.toString(), landscape.toString()),
                        () -> { }, openedPhoto::set);
            });
            EventPhotoGallery gallery = holder[0];
            waitForPhoto(gallery);
            SwingUtilities.invokeAndWait(() -> {
                require(!find(gallery, "photoGalleryFooter", JPanel.class).isVisible(),
                        "Navigation should overlay the image instead of reserving a footer row");
                JPanel canvas = find(gallery, "eventPhotoPreview", JPanel.class);
                require(SwingUtilities.isDescendingFrom(find(gallery, "nextEventPhoto", JButton.class), canvas),
                        "Navigation must be integrated into the image");
                BufferedImage painted = render(gallery, 300, 210);
                require(painted.getRGB(4, 105) == Color.MAGENTA.getRGB()
                                && painted.getRGB(295, 105) == Color.MAGENTA.getRGB(),
                        "The thumbnail must fill its frame without side bands");
                var clipped = new BufferedImage(300, 210, BufferedImage.TYPE_INT_RGB);
                var clippedGraphics = clipped.createGraphics();
                clippedGraphics.setColor(Color.YELLOW);
                clippedGraphics.fillRect(0, 0, 300, 210);
                clippedGraphics.setClip(0, 0, 300, 40);
                gallery.paint(clippedGraphics);
                clippedGraphics.dispose();
                require(clipped.getRGB(150, 105) == Color.YELLOW.getRGB(),
                        "A scrolled photo must respect the viewport clip and never cover fixed form actions");
                canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_CLICKED, 0, 0,
                        150, 100, 1, false, MouseEvent.BUTTON1));
                require(openedPhoto.get() == 0, "Click should enlarge the selected photo");
                require(find(gallery, "photoCounter", JLabel.class).getText().equals("1 / 2"),
                        "A record can have a list of several photographs");
                find(gallery, "nextEventPhoto", JButton.class).doClick();
                require(find(gallery, "photoCounter", JLabel.class).getText().equals("2 / 2"),
                        "Next must navigate within this record's photos");
            });
            waitForPhoto(gallery);
            SwingUtilities.invokeAndWait(() -> {
                JPanel canvas = find(gallery, "eventPhotoPreview", JPanel.class);
                canvas.getActionMap().get("openPhoto").actionPerformed(null);
                require(openedPhoto.get() == 1, "Keyboard opening must use the currently selected photo");
                find(gallery, "previousEventPhoto", JButton.class).doClick();
                require(find(gallery, "photoCounter", JLabel.class).getText().equals("1 / 2"),
                        "Previous must navigate back without changing the record");
                holder[0] = new EventPhotoGallery(List.of(root.resolve("missing.png").toString()),
                        null, openedPhoto::set);
            });
            for (int attempt = 0; attempt < 100; attempt++) {
                boolean[] done = {false};
                SwingUtilities.invokeAndWait(() -> done[0] = "missing".equals(
                        find(holder[0], "eventPhotoPreview", javax.swing.JPanel.class).getClientProperty("photoState")));
                if (done[0]) break;
                if (attempt == 99) throw new AssertionError("Missing files must show a safe placeholder");
                Thread.sleep(20);
            }
            SwingUtilities.invokeAndWait(() -> {
                find(holder[0], "eventPhotoPreview", JPanel.class).getActionMap().get("openPhoto")
                        .actionPerformed(null);
                require(openedPhoto.get() == 1, "A missing file must not offer a broken enlarged view");
                holder[0].dispose();
                holder[0] = EventPhotoGallery.expanded(List.of(portrait.toString()), 0);
            });
            waitForPhoto(holder[0]);
            SwingUtilities.invokeAndWait(() -> {
                BufferedImage expanded = render(holder[0], 800, 600);
                require(expanded.getRGB(400, 5) == Color.RED.getRGB()
                                && expanded.getRGB(400, 594) == Color.BLUE.getRGB(),
                        "The enlarged view must show the complete photo, without cropping its ends");
                require(find(holder[0], "addEventPhoto", JButton.class) == null,
                        "The enlarged viewer must remain read-only");
                require(!find(holder[0], "previousEventPhoto", JButton.class).isVisible()
                                && !find(holder[0], "nextEventPhoto", JButton.class).isVisible()
                                && !find(holder[0], "photoCounter", JLabel.class).isVisible(),
                        "The enlarged viewer must also hide navigation for a single image");
                gallery.dispose();
                holder[0].dispose();
            });
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
        System.out.println("EventPhotoGalleryTest: PASS");
    }

    private static void waitForPhoto(EventPhotoGallery gallery) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            boolean[] loaded = {false};
            SwingUtilities.invokeAndWait(() -> loaded[0] = "loaded".equals(
                    find(gallery, "eventPhotoPreview", javax.swing.JPanel.class).getClientProperty("photoState")));
            if (loaded[0]) return;
            Thread.sleep(20);
        }
        throw new AssertionError("The image should load asynchronously");
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
        for (Component child : parent.getComponents()) {
            if (child instanceof Container container) layout(container);
        }
    }

    private static BufferedImage render(EventPhotoGallery gallery, int width, int height) {
        gallery.setSize(width, height);
        layout(gallery);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        gallery.paint(graphics);
        graphics.dispose();
        return image;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
