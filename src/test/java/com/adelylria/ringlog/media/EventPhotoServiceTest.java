package com.adelylria.ringlog.media;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import com.adelylria.ringlog.application.DataMutationCoordinator;
import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.input.BirdEventInput;
import com.adelylria.ringlog.importexport.ImportCoordinator;
import com.adelylria.ringlog.importexport.nativeformat.RingLogExporter;
import com.adelylria.ringlog.importexport.service.ImportTransactionService;
import com.adelylria.ringlog.repository.BirdEventRepository;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.MediaPathResolver;

public final class EventPhotoServiceTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("ringlog-added-photos-");
        try {
            AppPaths paths = AppPaths.forDataRoot(root.resolve("source"));
            Database.initialize(paths.databasePath().toString());
            sql(paths, "INSERT INTO species (id, stable_key, scientific_name) VALUES (1, '00000000-0000-0000-0000-000000000001', 'Turdus test')");
            sql(paths, "INSERT INTO bird (id, stable_key, species_id, ring_number) VALUES (1, '00000000-0000-0000-0000-000000000002', 1, 'TEST-1')");
            sql(paths, "INSERT INTO bird_event (id, stable_key, bird_id, event_type, event_date) VALUES (1, '00000000-0000-0000-0000-000000000003', 1, 'RINGING', '2026-01-01')");
            sql(paths, "INSERT INTO bird_event (id, stable_key, bird_id, event_type, event_date) VALUES (2, '00000000-0000-0000-0000-000000000004', 1, 'CONTROL', '2026-02-01')");
            DataMutationCoordinator mutations = new DataMutationCoordinator();
            EventPhotoService service = new EventPhotoService(paths, mutations);
            BirdEventRepository events = new BirdEventRepository(paths.databasePath().toString());
            Path original = root.resolve("ave.png");
            BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
            ImageIO.write(image, "png", original.toFile());
            byte[] originalBytes = Files.readAllBytes(original);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(originalBytes));

            require(service.addPhoto(1, original), "The first photo should be added");
            require(!service.addPhoto(1, original), "Repeated clicks must not duplicate the same photo");
            require(events.findDetail(1).orElseThrow().photoPaths().size() == 1,
                    "The selected event should contain its photo");
            require(events.findDetail(2).orElseThrow().photoPaths().isEmpty(),
                    "Other events of the same bird must not inherit the photograph");
            Path managed = Path.of(events.findDetail(1).orElseThrow().photoPaths().get(0));
            require(managed.startsWith(paths.eventPhotosDirectory()) && !managed.equals(original),
                    "The photo must be an independent managed copy");
            require(Files.mismatch(original, managed) == -1, "Original bytes must be preserved");
            try (var connection = Database.getConnection(paths.databasePath().toString());
                 var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT * FROM event_photo")) {
                require(row.next() && row.getString("file_path").startsWith("events/"),
                        "Only relative managed references may be stored");
                require(hash.equals(row.getString("content_sha256"))
                                && row.getLong("content_size") == originalBytes.length
                                && "image/png".equals(row.getString("mime_type"))
                                && !row.getString("stable_key").isBlank(),
                        "New photos need stable identity, MIME type, hash and size for backups");
            }
            Path second = root.resolve("ave-2.jpg");
            ImageIO.write(image, "jpg", second.toFile());
            require(service.addPhoto(1, second), "A record should support several photographs");
            Files.delete(original);
            require(Files.isRegularFile(managed), "Deleting the user's source must not break the saved photo");

            var exported = new RingLogExporter(paths.databasePath().toString(), new MediaPathResolver(paths))
                    .exportTo(root.resolve("photo-backup"));
            require(exported.file().toString().endsWith(".zip") && exported.binaryCount() == 2,
                    "Native export must include all added photos as ZIP binaries");
            AppPaths restored = AppPaths.forDataRoot(root.resolve("restored"));
            try (var plan = new ImportCoordinator().analyze(exported.file())) {
                new ImportTransactionService(restored.databasePath().toString(), restored).execute(plan);
            }
            var restoredPhotos = new BirdEventRepository(restored.databasePath().toString())
                    .findDetail(1).orElseThrow().photoPaths();
            require(restoredPhotos.size() == 2 && photoState(paths).equals(photoState(restored)),
                    "Restore must retain stable photo identities, event association and exact bytes, independent of SQLite IDs/order");

            Path invalid = root.resolve("not-an-image.png");
            Files.writeString(invalid, "not an image");
            expectFailure(() -> service.addPhoto(1, invalid));
            Path disguised = root.resolve("disguised.png");
            ImageIO.write(image, "gif", disguised.toFile());
            expectFailure(() -> service.addPhoto(1, disguised));
            require(PhotoImages.load(disguised, 400, 300).image() != null,
                    "Existing imported GIFs must remain viewable even though the picker accepts JPG/PNG");
            require("image/gif".equals(PhotoImages.load(disguised, 400, 300).mimeType()),
                    "Legacy previews must report their actual format");
            expectFailure(() -> service.addPhoto(999, second));
            require(countFiles(paths) == 2, "Invalid input must not leave managed images behind");

            sql(paths, "CREATE TRIGGER reject_photo BEFORE INSERT ON event_photo BEGIN SELECT RAISE(ABORT, 'fixture failure'); END");
            expectFailure(() -> service.addPhoto(2, second));
            require(countFiles(paths) == 2 && events.findDetail(2).orElseThrow().photoPaths().isEmpty(),
                    "Failed persistence must roll back the newly published file and photo row");
            sql(paths, "DROP TRIGGER reject_photo");

            var executor = Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<Boolean> pending;
                try (var snapshot = mutations.acquireConsistentSnapshot()) {
                    pending = executor.submit(() -> service.addPhoto(2, second));
                    Thread.sleep(150);
                    require(!pending.isDone(), "A portable snapshot must block photo mutations");
                    require(events.findDetail(2).orElseThrow().photoPaths().isEmpty(),
                            "DB and media must remain unchanged during the snapshot");
                }
                require(pending.get(5, TimeUnit.SECONDS), "The photo should save after the snapshot finishes");
            } finally {
                executor.shutdownNow();
            }
            require(Files.isRegularFile(second), "Adding photos must never move or delete source images");

            Path batchGood = root.resolve("batch-good.png");
            Path batchFail = root.resolve("batch-fail.png");
            image.setRGB(0, 0, 0xff0000);
            ImageIO.write(image, "png", batchGood.toFile());
            image.setRGB(0, 0, 0x00ff00);
            ImageIO.write(image, "png", batchFail.toFile());
            var before = events.findDetail(2).orElseThrow();
            long filesBefore = countFiles(paths);
            BirdEventInput updated = input("TEST-1", EventType.CONTROL);
            expectFailure(() -> service.saveEvent(2L, updated, List.of(batchGood, invalid)));
            require(events.findDetail(2).orElseThrow().equals(before) && countFiles(paths) == filesBefore,
                    "One invalid image must leave both event fields and all photos unchanged");
            sql(paths, "CREATE TRIGGER reject_batch BEFORE INSERT ON event_photo WHEN NEW.file_name='batch-fail.png' BEGIN SELECT RAISE(ABORT, 'fixture failure'); END");
            expectFailure(() -> service.saveEvent(2L, updated, List.of(batchGood, batchFail)));
            require(events.findDetail(2).orElseThrow().equals(before) && countFiles(paths) == filesBefore,
                    "A later photo failure must roll back the event edit, earlier photo rows and new files");
            sql(paths, "DROP TRIGGER reject_batch");
            require(service.saveEvent(2L, updated, List.of(batchGood, batchFail)) == 2L,
                    "Saving an edit must retain the event identity");
            var saved = events.findDetail(2).orElseThrow();
            require("Cambios con fotos".equals(saved.observations()) && saved.photoPaths().size() == 3,
                    "Event fields and all pending photos must be saved together, retaining previous photos");
            service.saveEvent(2L, updated, List.of(batchGood, batchGood, batchFail));
            require(events.findDetail(2).orElseThrow().photoPaths().size() == 3
                            && countFiles(paths) == filesBefore + 2,
                    "Retries and duplicate selections must leave no duplicate rows or orphan files");
            expectFailure(() -> service.saveEvent(null, input("NEW-1", EventType.RINGING), List.of(invalid)));
            require(events.findBirdByRingNumber("NEW-1").isEmpty(),
                    "An invalid photo must not create a partial bird or event");
            long newId = service.saveEvent(null, input("NEW-1", EventType.RINGING), List.of(batchGood));
            require(events.findDetail(newId).orElseThrow().photoPaths().size() == 1,
                    "The same form must support photos for a new record");
            try (var files = Files.list(paths.migrationDirectory())) {
                require(files.noneMatch(file -> file.getFileName().toString().startsWith(".photo-")),
                        "Successful and failed saves must clean up their staging files");
            }
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
        System.out.println("EventPhotoServiceTest: PASS");
    }

    private static long countFiles(AppPaths paths) throws Exception {
        try (var files = Files.walk(paths.photosDirectory())) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private static BirdEventInput input(String ring, EventType type) {
        return new BirdEventInput(ring, 1L, type, "2026-03-01", "12:00", null, null,
                null, "4", null, null, null, null, null, null, null, null, null,
                null, null, null, 75.0, "Cambios con fotos", null, null, null, null, null, false);
    }

    private static java.util.Map<String, String> photoState(AppPaths paths) throws Exception {
        var state = new java.util.TreeMap<String, String>();
        try (var connection = Database.getConnection(paths.databasePath().toString());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT p.*, e.stable_key AS event_key FROM event_photo p JOIN bird_event e ON e.id=p.event_id")) {
            while (rows.next()) {
                Path file = new MediaPathResolver(paths).resolveEventPhoto(rows.getString("file_path"));
                String actualHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(file)));
                require(actualHash.equals(rows.getString("content_sha256"))
                                && Files.size(file) == rows.getLong("content_size"),
                        "Restored media must match stored checksums and sizes");
                state.put(rows.getString("stable_key"), rows.getString("event_key") + '|'
                        + rows.getString("file_name") + '|' + rows.getString("mime_type") + '|' + actualHash);
            }
        }
        return state;
    }

    private static void sql(AppPaths paths, String sql) throws Exception {
        try (var connection = Database.getConnection(paths.databasePath().toString());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void expectFailure(Operation operation) throws Exception {
        try {
            operation.run();
            throw new AssertionError("The invalid photo operation should fail");
        } catch (java.io.IOException | IllegalArgumentException | IllegalStateException expected) {
            // The user's files and existing photo rows must remain intact.
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private interface Operation { void run() throws Exception; }
}
