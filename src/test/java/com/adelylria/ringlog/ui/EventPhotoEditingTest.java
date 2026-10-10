package com.adelylria.ringlog.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import com.adelylria.ringlog.application.DataMutationCoordinator;
import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.media.EventPhotoService;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.repository.CatalogRepository;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.ui.components.EventPhotoEditor;
import com.adelylria.ringlog.ui.components.EventPhotoGallery;
import com.adelylria.ringlog.ui.panels.CaptureDetailPanel;
import com.adelylria.ringlog.ui.panels.CapturePanel;

public final class EventPhotoEditingTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("ringlog-photo-editing-");
        try {
            AppPaths paths = AppPaths.forDataRoot(root.resolve("data"));
            Database.initialize(paths.databasePath().toString());
            try (var db = Database.getConnection(paths.databasePath().toString()); var sql = db.createStatement()) {
                sql.execute("INSERT INTO species (id, stable_key, scientific_name) VALUES (1,'species-test','Test species')");
                sql.execute("INSERT INTO bird (id, stable_key, ring_number, species_id) VALUES (1,'bird-test','TEST-1',1)");
                sql.execute("INSERT INTO bird_event (id,stable_key,bird_id,event_type,event_date,observations) VALUES (1,'event-test',1,'RINGING','2026-01-01','Notas originales')");
            }
            BirdEventRepository events = new BirdEventRepository(paths);
            var before = events.findDetail(1).orElseThrow();
            Path image = root.resolve("new.png");
            ImageIO.write(new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
            Path invalid = root.resolve("invalid.png");
            Files.writeString(invalid, "invalid fixture");
            AtomicLong saved = new AtomicLong(-1);
            CapturePanel[] holder = new CapturePanel[1];
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new CapturePanel(events, new CatalogRepository(paths.databasePath().toString()),
                        saved::set, () -> { }, new EventPhotoService(paths, new DataMutationCoordinator()),
                        new EventPhotoEditor(pane -> true));
                holder[0].edit(before);
                holder[0].setDiscardConfirmation(pane -> true);
            });
            CapturePanel form = holder[0];
            await(() -> find(form, "speciesField", JComboBox.class).getItemCount() > 0, "Catalogs must load");
            EventPhotoEditor editor = find(form, "eventPhotoEditor", EventPhotoEditor.class);
            require(editor != null, "The photo editor must live in the record form");
            SwingUtilities.invokeAndWait(() -> {
                editor.addSelection(List.of(image, image));
                require(editor.pendingPhotos().size() == 1, "Repeated selections should not duplicate the draft");
                require(find(editor, "pendingPhotoStatus", JLabel.class).getText().contains("Sin guardar"),
                        "New photos must be clearly labelled as unsaved");
                find(form, "cancelEventEdit", JButton.class).doClick();
                require(editor.pendingPhotos().isEmpty(), "Cancel must discard all new selections");
            });
            require(events.findDetail(1).orElseThrow().equals(before) && photoFiles(paths) == 0,
                    "Selecting and cancelling must not write fields, photo rows or managed media");

            SwingUtilities.invokeAndWait(() -> {
                saved.set(-1);
                form.edit(before);
                editor.addSelection(List.of(image, invalid));
                find(form, "observationsField", JTextArea.class).setText("Notas con fotos");
                find(form, "saveEventButton", JButton.class).doClick();
                require(!find(editor, "addEventPhoto", JButton.class).isEnabled()
                                && !find(form, "cancelEventEdit", JButton.class).isEnabled(),
                        "Photo selection and cancellation must be disabled while saving");
                form.setMutationActionsEnabled(true);
                require(!find(form, "saveEventButton", JButton.class).isEnabled(),
                        "Mutation availability must not enable a second concurrent save");
            });
            await(() -> find(form, "saveEventButton", JButton.class).isEnabled(), "The failed save must unlock the form");
            require(saved.get() == -1 && events.findDetail(1).orElseThrow().equals(before) && photoFiles(paths) == 0,
                    "A bad image must not partially save fields or invoke the success callback");
            SwingUtilities.invokeAndWait(() -> {
                require(editor.pendingPhotos().size() == 2, "Failed saves must preserve selections for correction");
                find(editor, "discardPendingPhotos", JButton.class).doClick();
                editor.addSelection(List.of(image));
                find(form, "saveEventButton", JButton.class).doClick();
            });
            await(() -> saved.get() == 1, "Saving should return to the selected record");
            var after = events.findDetail(1).orElseThrow();
            require(after.photoPaths().size() == 1 && "Notas con fotos".equals(after.observations()),
                    "Fields and new photos must be persisted by the same Save action");
            require(Files.mismatch(image, Path.of(after.photoPaths().get(0))) == -1,
                    "The saved photo must be an unmodified managed copy");
            SwingUtilities.invokeAndWait(() -> {
                form.edit(after);
                editor.addSelection(List.of(invalid));
                find(form, "cancelEventEdit", JButton.class).doClick();
                form.edit(after);
                require(editor.pendingPhotos().isEmpty(), "Reopening must not revive cancelled selections");
                require(find(editor, "eventPhotoGallery", EventPhotoGallery.class) != null,
                        "Existing photos must remain visible while editing");
                editor.addSelection(List.of(invalid));
                form.prepareNew();
                require(editor.pendingPhotos().isEmpty(), "Draft photos must not leak into another record");
            });
            require(events.findDetail(1).orElseThrow().equals(after) && photoFiles(paths) == 1,
                    "Cancelling another edit must retain every saved photo and field");

            CaptureDetailPanel[] details = new CaptureDetailPanel[1];
            SwingUtilities.invokeAndWait(() -> {
                details[0] = new CaptureDetailPanel(events, () -> { }, ignored -> { }, true, root.resolve("map-cache"));
                details[0].setEventId(1);
            });
            await(() -> find(details[0], "eventPhotoGallery", EventPhotoGallery.class) != null, "The detail must load");
            SwingUtilities.invokeAndWait(() -> {
                require(find(details[0], "addEventPhoto", JButton.class) == null,
                        "Even a writable application's detail view must not contain an add-photo button");
                require(find(details[0], "removeEventPhoto", JButton.class) == null,
                        "Photo deletion must only be available in edit mode");
                find(details[0], "eventPhotoGallery", EventPhotoGallery.class).dispose();
            });

            SwingUtilities.invokeAndWait(() -> {
                form.edit(after);
                find(editor, "removeEventPhoto", JButton.class).doClick();
                require(editor.removedPhotos().equals(after.photoPaths()), "Confirmation must stage only the selected photo");
                find(form, "cancelEventEdit", JButton.class).doClick();
                require(editor.removedPhotos().isEmpty(), "Cancel must undo removal");
            });
            require(events.findDetail(1).orElseThrow().equals(after) && photoFiles(paths) == 1,
                    "Cancelling deletion must not touch the DB or media");
            SwingUtilities.invokeAndWait(() -> {
                saved.set(-1);
                form.edit(after);
                find(editor, "removeEventPhoto", JButton.class).doClick();
                find(form, "saveEventButton", JButton.class).doClick();
            });
            await(() -> saved.get() == 1, "Deletion-only form saves must complete");
            require(events.findDetail(1).orElseThrow().photoPaths().isEmpty() && photoFiles(paths) == 0,
                    "Save must remove both the association and its unshared managed file");
            require(Files.isRegularFile(image), "Deleting an app photo must never remove the user's original");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
        System.out.println("EventPhotoEditingTest: PASS");
    }

    private static long photoFiles(AppPaths paths) throws Exception {
        if (!Files.exists(paths.photosDirectory())) return 0;
        try (var files = Files.walk(paths.photosDirectory())) { return files.filter(Files::isRegularFile).count(); }
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        for (int i = 0; i < 200; i++) {
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

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
