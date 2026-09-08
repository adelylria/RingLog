package com.adelylria.ringlog.importexport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;

import com.adelylria.ringlog.backup.BackupExportService;
import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.importexport.service.ImportExecutionResult;
import com.adelylria.ringlog.importexport.service.ImportTransactionService;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.MediaPathResolver;
import com.adelylria.ringlog.storage.PhotoArea;

/** Compatibility contract for the already released self-contained backup v3. */
public final class RingLogV3CompatibilityTest {

    private static final byte[] PHOTO = "backup-v3-photo".getBytes(StandardCharsets.UTF_8);
    private static final String LONG_NOTES = "nota larga ".repeat(4_000);

    private RingLogV3CompatibilityTest() {
    }

    public static void currentBackupRestoresFreshAndExistingDatabasesWithoutReviewWizard()
            throws Exception {
        Path root = Files.createTempDirectory("ringlog-v3-compatibility-");
        AppPaths sourcePaths = AppPaths.forDataRoot(root.resolve("source-data"));
        AppPaths targetPaths = AppPaths.forDataRoot(root.resolve("target-data"));
        Path source = sourcePaths.databasePath();
        Path target = targetPaths.databasePath();
        Path originalPhoto = sourcePaths.eventPhotosDirectory().resolve("fixture/original.jpg");
        Path backup = root.resolve("ringlog-backup.xlsx");
        try {
            Files.createDirectories(originalPhoto.getParent());
            Files.write(originalPhoto, PHOTO);
            createSource(source, sourcePaths, originalPhoto);
            new BackupExportService(
                    source.toString(), new MediaPathResolver(sourcePaths)
            ).exportTo(backup);

            try (ImportPlan plan = new ImportCoordinator().analyze(backup)) {
                require(plan.profile().format() == ImportFormat.RINGLOG_BACKUP_V3,
                        "The released backup must use the dedicated v3 profile");
                require(plan.backupV3Model().events().size() == 2
                                && plan.backupV3Model().photos().size() == 1,
                        "The compatibility reader must expose every backup row and photo");
                require(!plan.requiresConflictReview(),
                        "Restoring a RingLog backup must never launch conflict resolution");

                ImportTransactionService fresh = new ImportTransactionService(
                        target.toString(), targetPaths
                );
                require(fresh.execute(plan).status() == ImportExecutionResult.Status.APPLIED,
                        "A v3 backup must restore into a fresh database");
                assertRestored(target, targetPaths);
                require(fresh.execute(plan).status()
                                == ImportExecutionResult.Status.ALREADY_IMPORTED,
                        "The same backup must be idempotent by canonical content");

                ImportTransactionService existing = new ImportTransactionService(
                        source.toString(), sourcePaths
                );
                require(existing.execute(plan).status() == ImportExecutionResult.Status.APPLIED,
                        "A backup may be restored safely over its source database");
                require(count(source, "bird_event") == 2 && count(source, "event_photo") == 1,
                        "Restoring over the source must not duplicate stable entities");
            }
        } finally {
            deleteDirectory(root);
        }
    }

    private static void createSource(Path database, AppPaths paths, Path photo) throws Exception {
        Database.initialize(database.toString());
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO species(stable_key, code, scientific_name, common_name,
                                        active, created_at, updated_at)
                    VALUES ('11111111-1111-4111-8111-111111111112', 'TUR-PHI',
                            'Turdus philomelos', 'Zorzal común', 1,
                            '2024-01-01 08:00:00', '2024-01-02 08:00:00')
                    """);
            statement.executeUpdate("""
                    INSERT INTO bird(stable_key, ring_number, species_id, created_at, updated_at)
                    VALUES ('22222222-2222-4222-8222-222222222223', 'V100', 1,
                            '2024-01-01 08:05:00', NULL)
                    """);
            statement.executeUpdate("""
                    INSERT INTO place(stable_key, name, locality, latitude, longitude, notes,
                                      is_favorite, is_default, active, created_at, updated_at)
                    VALUES ('33333333-3333-4333-8333-333333333334', 'ELS RAFALS',
                            'POLLENÇA', 39.95, 3.01, 'temporal', 1, 1, 1,
                            '2024-01-01 08:10:00', '2024-01-02 08:10:00')
                    """);
            statement.executeUpdate("""
                    INSERT INTO bird_event(stable_key, bird_id, event_type, event_date,
                                           event_time, place_id, observations, is_dead,
                                           source_name, source_reference, created_at, updated_at)
                    VALUES ('44444444-4444-4444-8444-444444444445', 1, 'RINGING',
                            '2024-02-03', '08:15', 1, 'Primera entrada', 0,
                            'Access', 'ACCESS:CAPTURAS:7',
                            '2024-02-03 08:20:00', '2024-02-03 08:25:00')
                    """);
            statement.executeUpdate("""
                    INSERT INTO bird_event(stable_key, bird_id, event_type, event_date,
                                           event_time, place_id, observations, is_dead,
                                           created_at, updated_at)
                    VALUES ('55555555-5555-4555-8555-555555555556', 1, 'CONTROL',
                            '2024-03-04', '17:45', 1, 'Entrada manual', 0,
                            '2024-03-04 17:50:00', NULL)
                    """);
            try (PreparedStatement notes = connection.prepareStatement(
                    "UPDATE place SET notes = ? WHERE id = 1"
            )) {
                notes.setString(1, LONG_NOTES);
                notes.executeUpdate();
            }
        }
        try (Connection connection = Database.getConnection(database.toString());
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO event_photo(stable_key, event_id, file_name, file_path,
                                             mime_type, created_at)
                     VALUES ('66666666-6666-4666-8666-666666666667', 2,
                             'original.jpg', ?, 'image/jpeg', '2024-03-04 18:00:00')
                     """)) {
            statement.setString(1, new MediaPathResolver(paths)
                    .toEventReference(PhotoArea.EVENTS, photo));
            statement.executeUpdate();
        }
    }

    private static void assertRestored(Path database, AppPaths paths) throws Exception {
        require(count(database, "species") == 1 && count(database, "bird") == 1
                        && count(database, "place") == 1 && count(database, "bird_event") == 2
                        && count(database, "event_photo") == 1,
                "The backup must restore every portable entity exactly once");
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT s.stable_key, p.stable_key, e.stable_key, ep.stable_key,
                            p.notes, s.created_at, e.updated_at, ep.file_path
                     FROM species s, place p, bird_event e
                     JOIN event_photo ep ON ep.event_id = e.id
                     """)) {
            require(result.next()
                            && "11111111-1111-4111-8111-111111111112".equals(result.getString(1))
                            && "33333333-3333-4333-8333-333333333334".equals(result.getString(2))
                            && "55555555-5555-4555-8555-555555555556".equals(result.getString(3))
                            && "66666666-6666-4666-8666-666666666667".equals(result.getString(4)),
                    "Backup v3 stable identities must survive restoration");
            require(LONG_NOTES.equals(result.getString(5))
                            && "2024-01-01 08:00:00".equals(result.getString(6))
                            && result.getString(7) == null,
                    "Long text, timestamps, and null distinctions must survive");
            String reference = result.getString(8);
            require(reference.startsWith("native/") && !Path.of(reference).isAbsolute(),
                    "Backup v3 media must use a managed native reference");
            Path restoredPhoto = new MediaPathResolver(paths).resolveEventPhoto(reference);
            require(restoredPhoto.startsWith(paths.nativePhotosDirectory())
                            && java.util.Arrays.equals(PHOTO, Files.readAllBytes(restoredPhoto)),
                    "Embedded photo bytes must be restored into managed storage");
        }
        require(count(database, "migration_conflict") == 0,
                "Backup restoration must not invent migration conflicts");
    }

    private static long count(Path database, String table) throws Exception {
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            require(result.next(), "Expected a count for " + table);
            return result.getLong(1);
        }
    }

    private static void deleteDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        currentBackupRestoresFreshAndExistingDatabasesWithoutReviewWizard();
        System.out.println("RingLogV3CompatibilityTest: PASS");
    }
}
