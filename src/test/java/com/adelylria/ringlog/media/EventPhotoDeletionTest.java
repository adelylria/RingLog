package com.adelylria.ringlog.media;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import javax.imageio.ImageIO;
import com.adelylria.ringlog.application.DataMutationCoordinator;
import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.input.BirdEventInput;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.storage.AppPaths;

public final class EventPhotoDeletionTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("ringlog-photo-deletion-");
        try {
            AppPaths paths = AppPaths.forDataRoot(root.resolve("data"));
            Database.initialize(paths.databasePath().toString());
            sql(paths, "INSERT INTO species (id, stable_key, scientific_name) VALUES (1,'00000000-0000-0000-0000-000000000001','Test species')");
            EventPhotoService service = new EventPhotoService(paths, new DataMutationCoordinator());
            BirdEventRepository events = new BirdEventRepository(paths);
            Path original = root.resolve("original.png");
            ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", original.toFile());
            byte[] originalBytes = Files.readAllBytes(original);
            long first = service.saveEvent(null, input("FIRST", "Before"), List.of(original));
            long other = service.saveEvent(null, input("OTHER", "Other"), List.of(original));
            var before = events.findDetail(first).orElseThrow();
            String photo = before.photoPaths().get(0);
            var otherBefore = events.findDetail(other).orElseThrow();

            expectFailure(() -> service.saveEvent(first, input("FIRST", "Wrong"), List.of(), otherBefore.photoPaths()));
            expectFailure(() -> service.saveEvent(first, input("FIRST", "Wrong"), List.of(), List.of(original.toString())));
            require(events.findDetail(first).orElseThrow().equals(before), "Unrelated paths must not remove photos or save fields");

            sql(paths, "CREATE TRIGGER reject_delete BEFORE DELETE ON event_photo BEGIN SELECT RAISE(ABORT,'fixture'); END");
            expectFailure(() -> service.saveEvent(first, input("FIRST", "After"), List.of(), List.of(photo)));
            require(events.findDetail(first).orElseThrow().equals(before) && Files.exists(Path.of(photo)),
                    "A failed delete must roll back all fields and retain the old file");
            sql(paths, "DROP TRIGGER reject_delete");
            sql(paths, "CREATE TRIGGER reject_add BEFORE INSERT ON event_photo BEGIN SELECT RAISE(ABORT,'fixture'); END");
            expectFailure(() -> service.saveEvent(first, input("FIRST", "After"), List.of(original), List.of(photo)));
            require(events.findDetail(first).orElseThrow().equals(before) && Files.exists(Path.of(photo)),
                    "Adding and deleting together must be atomic, including original bytes");
            sql(paths, "DROP TRIGGER reject_add");

            // Imported backups may contain two rows sharing a single physical file.
            sql(paths, "INSERT INTO event_photo (stable_key,event_id,file_name,file_path,mime_type) "
                    + "SELECT '00000000-0000-0000-0000-000000000002'," + other + ",file_name,file_path,mime_type FROM event_photo WHERE event_id=" + first);
            service.saveEvent(first, input("FIRST", "After"), List.of(), List.of(photo));
            require(events.findDetail(first).orElseThrow().photoPaths().isEmpty()
                            && "After".equals(events.findDetail(first).orElseThrow().observations()),
                    "Deletion-only saves must persist alongside field edits");
            require(Files.exists(Path.of(photo)) && events.findDetail(other).orElseThrow().photoPaths().contains(photo),
                    "Removing one reference must never delete another record's shared binary");
            service.saveEvent(other, input("OTHER", "Other"), List.of(), List.of(photo));
            require(!Files.exists(Path.of(photo)) && events.findDetail(other).orElseThrow().equals(otherBefore),
                    "The last reference removes only its owned binary; all other photos remain intact");
            expectFailure(() -> service.saveEvent(other, input("OTHER", "Stale"), List.of(), List.of(photo)));
            require(events.findDetail(other).orElseThrow().equals(otherBefore), "Stale deletion must not save unrelated fields");

            // Replace a removed photo with the very same image in one Save.
            String oldOther = otherBefore.photoPaths().get(0);
            service.saveEvent(other, input("OTHER", "Other"), List.of(original), List.of(oldOther));
            String replacement = events.findDetail(other).orElseThrow().photoPaths().get(0);
            require(!replacement.equals(oldOther) && !Files.exists(Path.of(oldOther))
                            && Files.mismatch(original, Path.of(replacement)) == -1,
                    "Removal must precede deduplication of additions inside the transaction");
            require(java.util.Arrays.equals(originalBytes, Files.readAllBytes(original)), "Never delete or change the user's source image");

            var exported = new com.adelylria.ringlog.importexport.nativeformat.RingLogExporter(
                    paths.databasePath().toString(), new com.adelylria.ringlog.storage.MediaPathResolver(paths))
                    .exportTo(root.resolve("after-deletion"));
            require(exported.binaryCount() == 1, "Backups must contain only remaining photos");
            try (var connection = Database.getConnection(paths.databasePath().toString()); var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("PRAGMA quick_check")) { require(rows.next() && "ok".equals(rows.getString(1)), "DB integrity"); }
                try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) { require(!rows.next(), "No broken foreign keys"); }
            }
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
        System.out.println("EventPhotoDeletionTest: PASS");
    }

    private static BirdEventInput input(String ring, String notes) {
        return new BirdEventInput(ring, 1L, EventType.RINGING, "2026-01-01", "12:00", null, null,
                null, "4", null, null, null, null, null, null, null, null, null,
                null, null, null, 75.0, notes, null, null, null, null, null, false);
    }
    private static void sql(AppPaths paths, String text) throws Exception {
        try (var connection = Database.getConnection(paths.databasePath().toString()); var statement = connection.createStatement()) {
            statement.execute(text);
        }
    }
    private static void expectFailure(Operation operation) throws Exception {
        try { operation.run(); throw new AssertionError("Expected deletion failure"); }
        catch (java.io.IOException | IllegalArgumentException expected) { }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private interface Operation { void run() throws Exception; }
}
