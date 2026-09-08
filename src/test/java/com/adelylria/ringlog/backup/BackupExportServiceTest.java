package com.adelylria.ringlog.backup;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.importer.ImportResult;
import com.adelylria.ringlog.importer.LegacyImportException;
import com.adelylria.ringlog.importer.LegacyImportService;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.MediaPathResolver;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;

public final class BackupExportServiceTest {

    private static final byte[] PHOTO_CONTENT =
            "foto-autocontenida-ringlog".getBytes(StandardCharsets.UTF_8);
    private static final String LONG_PLACE_NOTES = "a".repeat(29_999)
            + "🦜"
            + "b".repeat(5_000);

    private BackupExportServiceTest() {
    }

    public static void exportedWorkbookRestoresAllDataAndPhotoBytes()
            throws Exception {
        Fixture fixture = createFixture(true);
        try {
            BackupExportService exporter = new BackupExportService(
                    fixture.sourceDatabase().toString()
            );

            BackupExportResult exported = exporter.exportTo(fixture.backup());

            require(Files.isRegularFile(fixture.backup()),
                    "A successful export should create the selected workbook");
            require(exported.speciesCount() == 1, "Backup should count species");
            require(exported.birdsCount() == 1, "Backup should count birds");
            require(exported.placesCount() == 1, "Backup should count places");
            require(exported.eventsCount() == 2, "Backup should count events");
            require(exported.photosCount() == 1,
                    "Only linked event photos should be exported");
            assertSelfContainedVersionThreeWorkbook(fixture.backup());
            BackupExportResult replaced = exporter.exportTo(fixture.backup());
            require(replaced.eventsCount() == 2 && Files.isRegularFile(replaced.file()),
                    "A new verified backup should atomically replace an older one");

            LegacyImportService sameDatabaseImport = new LegacyImportService(
                    fixture.sourceDatabase().toString(),
                    fixture.sourceManagedPhotos()
            );
            ImportResult sameDatabaseResult = sameDatabaseImport.importWorkbook(
                    fixture.backup()
            );
            require(sameDatabaseResult.eventsCreated() == 0,
                    "Restoring over the source database must not duplicate manual events");
            require(sameDatabaseResult.eventsSkipped() == 2,
                    "Existing source events should be recognized during restore");
            try (Connection connection = Database.getConnection(
                    fixture.sourceDatabase().toString()
            ); Statement statement = connection.createStatement();
                 ResultSet manual = statement.executeQuery("""
                         SELECT source_name, source_reference, updated_at
                         FROM bird_event
                         WHERE event_type = 'CONTROL'
                         """)) {
                require(manual.next()
                                && manual.getString(1) == null
                                && manual.getString(2) == null
                                && manual.getString(3) == null,
                        "Restoring a backup must not rewrite manual-event provenance");
            }

            LegacyImportService freshInstallImport = new LegacyImportService(
                    fixture.targetDatabase().toString(),
                    fixture.targetManagedPhotos()
            );
            ImportResult restored = freshInstallImport.importWorkbook(fixture.backup());

            require(restored.speciesCreated() == 1, "Species should be restored");
            require(restored.birdsCreated() == 1, "Birds should be restored");
            require(restored.placesCreated() == 1, "Places should be restored");
            require(restored.eventsCreated() == 2, "Events should be restored");
            require(restored.photosCreated() == 1, "Linked photos should be restored");
            assertRestoredDatabase(fixture);
            assertPortableFieldsEqual(
                    fixture.sourceDatabase(),
                    fixture.targetDatabase()
            );

            ImportResult secondRestore = freshInstallImport.importWorkbook(fixture.backup());
            require(secondRestore.eventsCreated() == 0
                            && secondRestore.eventsSkipped() == 2,
                    "Importing the same backup twice must not duplicate events");
            require(secondRestore.photosCreated() == 0
                            && secondRestore.photosSkipped() == 1,
                    "Importing the same backup twice must not duplicate photos");

            Path restoredPhoto = storedPhotoPath(
                    fixture.targetDatabase(), fixture.targetManagedPhotos()
            );
            Files.delete(restoredPhoto);
            ImportResult repairedPhoto = freshInstallImport.importWorkbook(fixture.backup());
            require(repairedPhoto.photosCreated() == 0
                            && repairedPhoto.photosSkipped() == 1,
                    "Restoring a missing managed file should reuse its database row");
            require(java.util.Arrays.equals(
                            PHOTO_CONTENT,
                            Files.readAllBytes(restoredPhoto)
                    ),
                    "Importing a backup should recover a deleted linked photo file");

            try (Connection connection = Database.getConnection(
                    fixture.targetDatabase().toString()
            ); Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        DELETE FROM bird_event WHERE event_type = 'CONTROL'
                        """);
            }
            ImportResult recoveredEvent = freshInstallImport.importWorkbook(fixture.backup());
            require(recoveredEvent.eventsCreated() == 1
                            && recoveredEvent.eventsSkipped() == 1,
                    "Importing an older backup should recover a deleted event");
            require(recoveredEvent.photosCreated() == 1,
                    "Recovering a deleted event should restore its linked photo row");
        } finally {
            fixture.delete();
        }
    }

    public static void missingLinkedPhotoLeavesNoPartialBackup() throws Exception {
        Fixture fixture = createFixture(false);
        try {
            byte[] previousBackup = "copia-anterior-intacta"
                    .getBytes(StandardCharsets.UTF_8);
            Files.write(fixture.backup(), previousBackup);
            BackupExportService exporter = new BackupExportService(
                    fixture.sourceDatabase().toString()
            );

            requireThrows(
                    () -> exporter.exportTo(fixture.backup()),
                    "A full backup must fail when a linked photo is unavailable"
            );
            require(java.util.Arrays.equals(
                            previousBackup,
                            Files.readAllBytes(fixture.backup())
                    ),
                    "A failed export must preserve the previous backup intact");
        } finally {
            fixture.delete();
        }
    }

    public static void sameMinuteManualEventsAreNotMerged() throws Exception {
        Fixture fixture = createFixture(true);
        try {
            try (Connection connection = Database.getConnection(
                    fixture.sourceDatabase().toString()
            ); Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO bird_event(
                            stable_key, bird_id, event_type, event_date, event_time, place_id,
                            sex_code, status, observations, is_dead,
                            source_name, source_reference, created_at, updated_at
                        ) VALUES (
                            '99999999-9999-4999-8999-999999999999',
                            1, 'CONTROL', '2024-03-04', '17:45', 1,
                            'M', 'OK', 'Otro control ocurrido en el mismo minuto', 0,
                            NULL, NULL, '2024-03-04 17:51:00', NULL
                        )
                        """);
            }

            new BackupExportService(fixture.sourceDatabase().toString())
                    .exportTo(fixture.backup());
            LegacyImportService importService = new LegacyImportService(
                    fixture.sourceDatabase().toString(),
                    fixture.sourceManagedPhotos()
            );
            ImportResult result = importService.importWorkbook(fixture.backup());

            require(result.eventsCreated() == 0 && result.eventsSkipped() == 3,
                    "Events sharing bird, type, date and time must stay distinct");
            try (Connection connection = Database.getConnection(
                    fixture.sourceDatabase().toString()
            ); Statement statement = connection.createStatement();
                 ResultSet count = statement.executeQuery("""
                         SELECT COUNT(*), COUNT(source_reference)
                         FROM bird_event
                         WHERE event_type = 'CONTROL'
                         """)) {
                require(count.next()
                                && count.getInt(1) == 2
                                && count.getInt(2) == 0,
                        "Same-minute manual events must neither merge nor gain provenance");
            }
        } finally {
            fixture.delete();
        }
    }

    public static void absurdEmbeddedChunkCountIsRejectedSafely() throws Exception {
        Fixture fixture = createFixture(true);
        try {
            new BackupExportService(fixture.sourceDatabase().toString())
                    .exportTo(fixture.backup());
            Path manipulated = fixture.root().resolve("manipulated.xlsx");
            try (InputStream input = Files.newInputStream(fixture.backup());
                 Workbook workbook = WorkbookFactory.create(input)) {
                workbook.getSheet("photo_content")
                        .getRow(1)
                        .getCell(2)
                        .setCellValue(Integer.MAX_VALUE);
                try (java.io.OutputStream output = Files.newOutputStream(manipulated)) {
                    workbook.write(output);
                }
            }

            LegacyImportService importer = new LegacyImportService(
                    fixture.targetDatabase().toString(),
                    fixture.targetManagedPhotos()
            );
            requireImportThrows(
                    () -> importer.preview(manipulated),
                    "An absurd chunk count must be rejected without allocating it"
            );
        } finally {
            fixture.delete();
        }
    }

    public static void newerManualEventMomentIsPreservedWithoutDuplication()
            throws Exception {
        Fixture fixture = createFixture(true);
        try {
            new BackupExportService(fixture.sourceDatabase().toString())
                    .exportTo(fixture.backup());
            try (Connection connection = Database.getConnection(
                    fixture.sourceDatabase().toString()
            ); Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        UPDATE bird_event
                        SET event_date = '2024-04-05', event_time = '09:30'
                        WHERE event_type = 'CONTROL'
                        """);
            }

            ImportResult result = new LegacyImportService(
                    fixture.sourceDatabase().toString(),
                    fixture.sourceManagedPhotos()
            ).importWorkbook(fixture.backup());

            require(result.eventsCreated() == 0 && result.eventsSkipped() == 2,
                    "A newer manual date/time edit must not create an older duplicate");
            try (Connection connection = Database.getConnection(
                    fixture.sourceDatabase().toString()
            ); Statement statement = connection.createStatement();
                 ResultSet event = statement.executeQuery("""
                         SELECT COUNT(*), MAX(event_date), MAX(event_time)
                         FROM bird_event WHERE event_type = 'CONTROL'
                         """)) {
                require(event.next()
                                && event.getInt(1) == 1
                                && "2024-04-05".equals(event.getString(2))
                                && "09:30".equals(event.getString(3)),
                        "The newer manual event moment should remain untouched");
            }
        } finally {
            fixture.delete();
        }
    }

    private static Fixture createFixture(boolean createPhoto) throws Exception {
        Path root = Files.createTempDirectory("ringlog-backup-test-");
        Path sourceDatabase = root.resolve("source.db");
        Path targetDatabase = root.resolve("fresh-install.db");
        Path sourcePhoto = root.resolve("photos/events/fixture/original.jpg");
        Path backup = root.resolve("RingLog-copia.xlsx");
        Path sourceManagedPhotos = root;
        Path targetManagedPhotos = root.resolve("fresh-install-photos");

        Database.initialize(sourceDatabase.toString());
        if (createPhoto) {
            Files.createDirectories(sourcePhoto.getParent());
            Files.write(sourcePhoto, PHOTO_CONTENT);
        }

        try (Connection connection = Database.getConnection(sourceDatabase.toString());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO species(
                        stable_key, code, scientific_name, common_name, active,
                        created_at, updated_at
                    ) VALUES (
                        '11111111-1111-4111-8111-111111111112',
                        'TUR-PHI', 'Turdus philomelos', 'Zorzal común', 1,
                        '2024-02-01 09:00:00', '2024-02-02 10:00:00'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO bird(
                        stable_key, ring_number, species_id, created_at, updated_at
                    ) VALUES (
                        '22222222-2222-4222-8222-222222222223',
                        'V100', 1, '2024-02-01 09:05:00', '2024-02-02 10:05:00'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO place(
                        stable_key, name, locality, latitude, longitude, notes,
                        is_favorite, is_default, active, created_at, updated_at
                    ) VALUES (
                        '33333333-3333-4333-8333-333333333334',
                        'ELS RAFALS', 'POLLENÇA', 39.95, 3.01, 'Lugar habitual',
                        1, 1, 1, '2024-02-01 09:10:00', '2024-02-02 10:10:00'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO bird_event(
                        stable_key, bird_id, event_type, event_date, event_time, place_id,
                        sex_code, age_euring_code, fat_score, muscle_score,
                        ringer_initials, status, observations, weight, is_dead,
                        source_name, source_reference, created_at, updated_at
                    ) VALUES (
                        '44444444-4444-4444-8444-444444444445',
                        1, 'RINGING', '2024-02-03', '08:15', 1,
                        'U', '4', 2, 3, 'AA', 'OK', 'Entrada heredada', 71.5, 0,
                        'Access', 'ACCESS:CAPTURAS:7',
                        '2024-02-03 08:20:00', '2024-02-03 08:25:00'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO bird_event(
                        stable_key, bird_id, event_type, event_date, event_time, place_id,
                        location_text, sex_code, status, observations, is_dead,
                        source_name, source_reference, created_at, updated_at
                    ) VALUES (
                        '55555555-5555-4555-8555-555555555556',
                        1, 'CONTROL', '2024-03-04', '17:45', 1,
                        'Junto al observatorio', 'F', 'OK',
                        'Entrada creada directamente en RingLog', 0,
                        NULL, NULL, '2024-03-04 17:50:00', NULL
                    )
                    """);
        }
        try (Connection connection = Database.getConnection(sourceDatabase.toString());
             PreparedStatement photo = connection.prepareStatement("""
                     INSERT INTO event_photo(
                         stable_key, event_id, file_name, file_path, mime_type, created_at
                     ) VALUES ('66666666-6666-4666-8666-666666666667',
                               2, 'original.jpg', ?, 'image/jpeg',
                               '2024-03-04 18:00:00')
                     """)) {
            photo.setString(1, "events/fixture/original.jpg");
            photo.executeUpdate();
        }
        try (Connection connection = Database.getConnection(sourceDatabase.toString());
             PreparedStatement notes = connection.prepareStatement("""
                     UPDATE place SET notes = ?, updated_at = '' WHERE id = 1
                     """);
             Statement timestamps = connection.createStatement()) {
            notes.setString(1, LONG_PLACE_NOTES);
            notes.executeUpdate();
            timestamps.executeUpdate("""
                    UPDATE place
                    SET updated_at = '2024-02-02 10:10:00'
                    WHERE id = 1
                    """);
        }

        return new Fixture(
                root,
                sourceDatabase,
                targetDatabase,
                sourceManagedPhotos,
                targetManagedPhotos,
                backup
        );
    }

    private static void assertSelfContainedVersionThreeWorkbook(Path backup)
            throws Exception {
        try (InputStream input = Files.newInputStream(backup);
             Workbook workbook = WorkbookFactory.create(input)) {
            require("3".equals(metadataValue(workbook, "format_version")),
                    "RingLog backups should use import format version 3");
            require("RingLog Import".equals(metadataValue(workbook, "format")),
                    "The normal importer should recognize exported backups");
            int contentSheet = workbook.getSheetIndex("photo_content");
            require(contentSheet >= 0, "Photo bytes should be embedded in the workbook");
            require(workbook.isSheetHidden(contentSheet)
                            || workbook.isSheetVeryHidden(contentSheet),
                    "Internal photo content should stay hidden from normal users");
            int textSheet = workbook.getSheetIndex("text_content");
            require(textSheet >= 0
                            && (workbook.isSheetHidden(textSheet)
                            || workbook.isSheetVeryHidden(textSheet)),
                    "Long internal text should stay hidden from normal users");
            require(workbook.getSheet("unassigned_photos") == null,
                    "Photos not linked to events must never be exported");
            require("11111111-1111-4111-8111-111111111112".equals(
                            workbook.getSheet("species").getRow(1).getCell(0).getStringCellValue())
                            && "33333333-3333-4333-8333-333333333334".equals(
                            workbook.getSheet("places").getRow(1).getCell(0).getStringCellValue())
                            && "44444444-4444-4444-8444-444444444445".equals(
                            workbook.getSheet("events").getRow(1).getCell(0).getStringCellValue())
                            && "66666666-6666-4666-8666-666666666667".equals(
                            workbook.getSheet("photos").getRow(1).getCell(5).getStringCellValue()),
                    "Backup identities must use persistent stable keys, never SQLite IDs");
        }
    }

    private static void assertRestoredDatabase(Fixture fixture) throws Exception {
        try (Connection connection = Database.getConnection(
                fixture.targetDatabase().toString()
        ); Statement statement = connection.createStatement()) {
            require(count(statement, "species") == 1, "Restore duplicated species");
            require(count(statement, "bird") == 1, "Restore duplicated birds");
            require(count(statement, "place") == 1, "Restore duplicated places");
            require(count(statement, "bird_event") == 2, "Restore lost events");
            require(count(statement, "event_photo") == 1, "Restore lost photos");

            try (ResultSet species = statement.executeQuery("""
                    SELECT created_at, updated_at FROM species
                    """)) {
                require(species.next()
                                && "2024-02-01 09:00:00".equals(species.getString(1))
                                && "2024-02-02 10:00:00".equals(species.getString(2)),
                        "Backup restore should preserve catalog timestamps");
            }
            try (ResultSet place = statement.executeQuery("SELECT notes FROM place")) {
                require(place.next() && LONG_PLACE_NOTES.equals(place.getString(1)),
                        "Backup restore should preserve text beyond Excel's cell limit");
            }

            try (ResultSet events = statement.executeQuery("""
                    SELECT event_type, observations, source_reference
                    FROM bird_event
                    ORDER BY event_date
                    """)) {
                require(events.next(), "The legacy event should exist");
                require("ACCESS:CAPTURAS:7".equals(events.getString(3)),
                        "Legacy provenance should survive a backup round trip");
                require(events.next(), "The manual event should exist");
                require("CONTROL".equals(events.getString(1))
                                && "Entrada creada directamente en RingLog"
                                .equals(events.getString(2)),
                        "Manual event data should survive a backup round trip");
                require(events.getString(3) == null,
                        "Manual event provenance should remain null after restore");
            }

            try (ResultSet eventTime = statement.executeQuery("""
                    SELECT created_at, updated_at
                    FROM bird_event
                    WHERE event_type = 'CONTROL'
                    """)) {
                require(eventTime.next()
                                && "2024-03-04 17:50:00".equals(eventTime.getString(1))
                                && eventTime.getString(2) == null,
                        "Backup restore should preserve event timestamps");
            }

            try (ResultSet photo = statement.executeQuery(
                    "SELECT file_path FROM event_photo"
            )) {
                require(photo.next(), "Restored photo should be registered");
                String reference = photo.getString(1);
                require(reference.startsWith("events/") && !Path.of(reference).isAbsolute(),
                        "Restored photos must use schema v4 managed references");
                Path restoredFile = new MediaPathResolver(
                        AppPaths.forDataRoot(fixture.targetManagedPhotos())
                ).resolveEventPhoto(reference);
                require(restoredFile.startsWith(
                                fixture.targetManagedPhotos().resolve("photos/events")),
                        "Restored photos should use RingLog-managed storage");
                require(java.util.Arrays.equals(PHOTO_CONTENT, Files.readAllBytes(restoredFile)),
                        "Restored photo bytes must be identical to the exported image");
            }
            try (ResultSet photoTime = statement.executeQuery(
                    "SELECT created_at FROM event_photo"
            )) {
                require(photoTime.next()
                                && "2024-03-04 18:00:00".equals(photoTime.getString(1)),
                        "Backup restore should preserve photo timestamps");
            }
        }
    }

    private static String metadataValue(Workbook workbook, String key) {
        Sheet metadata = workbook.getSheet("metadata");
        if (metadata == null) {
            return null;
        }
        for (Row row : metadata) {
            if (row.getCell(0) != null && key.equals(row.getCell(0).getStringCellValue())) {
                return row.getCell(1).toString();
            }
        }
        return null;
    }

    private static long count(Statement statement, String table) throws Exception {
        try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            require(result.next(), "Count query should return a row");
            return result.getLong(1);
        }
    }

    private static Path storedPhotoPath(Path database, Path dataRoot) throws Exception {
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT file_path FROM event_photo"
             )) {
            require(result.next(), "A linked photo row should exist");
            return new MediaPathResolver(AppPaths.forDataRoot(dataRoot))
                    .resolveEventPhoto(result.getString(1));
        }
    }

    private static void assertPortableFieldsEqual(Path source, Path restored)
            throws Exception {
        for (String query : java.util.List.of(
                """
                SELECT code, scientific_name, common_name, active, created_at, updated_at
                FROM species ORDER BY scientific_name
                """,
                """
                SELECT b.ring_number, s.scientific_name, b.created_at, b.updated_at
                FROM bird b JOIN species s ON s.id = b.species_id
                ORDER BY b.ring_number
                """,
                """
                SELECT name, locality, latitude, longitude, notes,
                       is_favorite, is_default, active, created_at, updated_at
                FROM place ORDER BY name, locality
                """,
                """
                SELECT b.ring_number, e.event_type, e.event_date, e.event_time,
                       p.name, p.locality, e.location_text, e.sex_code,
                       e.age_euring_code, e.fat_score, e.muscle_score,
                       e.ringer_initials, e.status, e.reproductive_status,
                       e.moult_intensity, e.moult_extension, e.bird_condition,
                       e.return_status, e.wing, e.p3, e.torso, e.weight,
                       e.observations, e.clouds, e.rain, e.thermal_sensation,
                       e.wind, e.capture_type, e.is_dead, e.source_name,
                       e.source_reference, e.created_at, e.updated_at
                FROM bird_event e
                JOIN bird b ON b.id = e.bird_id
                LEFT JOIN place p ON p.id = e.place_id
                ORDER BY e.event_date, e.event_time, e.created_at
                """,
                """
                SELECT b.ring_number, e.event_type, e.event_date, e.event_time,
                       ep.file_name, ep.mime_type, ep.created_at
                FROM event_photo ep
                JOIN bird_event e ON e.id = ep.event_id
                JOIN bird b ON b.id = e.bird_id
                ORDER BY e.event_date, ep.file_name
                """
        )) {
            require(queryRows(source, query).equals(queryRows(restored, query)),
                    "Every portable database field should survive the round trip");
        }
    }

    private static java.util.List<java.util.List<String>> queryRows(
            Path database,
            String query
    ) throws Exception {
        java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(query)) {
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                java.util.List<String> row = new java.util.ArrayList<>(columns);
                for (int column = 1; column <= columns; column++) {
                    row.add(result.getString(column));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static void requireThrows(ThrowingRunnable action, String message)
            throws Exception {
        try {
            action.run();
        } catch (BackupExportException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void requireImportThrows(ThrowingRunnable action, String message)
            throws Exception {
        try {
            action.run();
        } catch (LegacyImportException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record Fixture(
            Path root,
            Path sourceDatabase,
            Path targetDatabase,
            Path sourceManagedPhotos,
            Path targetManagedPhotos,
            Path backup
    ) {
        void delete() throws Exception {
            if (!Files.exists(root)) {
                return;
            }
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        exportedWorkbookRestoresAllDataAndPhotoBytes();
        missingLinkedPhotoLeavesNoPartialBackup();
        sameMinuteManualEventsAreNotMerged();
        absurdEmbeddedChunkCountIsRejectedSafely();
        newerManualEventMomentIsPreservedWithoutDuplication();
        System.out.println("BackupExportServiceTest: PASS");
    }
}
