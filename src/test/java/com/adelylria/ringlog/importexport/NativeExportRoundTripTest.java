package com.adelylria.ringlog.importexport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.importexport.nativeformat.ExportResult;
import com.adelylria.ringlog.importexport.nativeformat.RingLogExporter;
import com.adelylria.ringlog.importexport.service.ConflictResolutionService;
import com.adelylria.ringlog.importexport.service.ImportExecutionResult;
import com.adelylria.ringlog.importexport.service.ImportTransactionService;
import com.adelylria.ringlog.importexport.service.ResolutionRequest;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.MediaPathResolver;
import com.adelylria.ringlog.storage.PhotoArea;
import com.adelylria.ringlog.testsupport.LegacyV52TestFixture;

/** Lossless native-v1 ZIP/XLSX and stable-key round-trip contract. */
public final class NativeExportRoundTripTest {

    private static final Path LEGACY_FIXTURE = LegacyV52TestFixture.path();

    private NativeExportRoundTripTest() {
    }

    public static void binaryStateUsesZipAndRestoresTraceabilityWithoutWizard()
            throws Exception {
        if (LegacyV52TestFixture.skipIfUnavailable("Native export legacy round-trip integration")) {
            return;
        }
        Path root = Files.createTempDirectory("ringlog-native-roundtrip-");
        AppPaths sourcePaths = AppPaths.forDataRoot(root.resolve("source-data"));
        AppPaths targetPaths = AppPaths.forDataRoot(root.resolve("target-data"));
        Path source = sourcePaths.databasePath();
        Path target = targetPaths.databasePath();
        try {
            try (ImportPlan legacy = new ImportCoordinator().analyze(LEGACY_FIXTURE)) {
                new ImportTransactionService(source.toString(), sourcePaths)
                        .execute(legacy);
            }
            long conflict = scalarLong(source, """
                    SELECT id FROM migration_conflict
                    WHERE status = 'PENDING_REVIEW' ORDER BY id LIMIT 1
                    """);
            new ConflictResolutionService(source.toString()).resolve(
                    conflict, ResolutionRequest.canonical("Verificado para round-trip")
            );
            addEventPhoto(source, sourcePaths);

            ExportResult exported = new RingLogExporter(
                    source.toString(), new MediaPathResolver(sourcePaths)
            )
                    .exportTo(root.resolve("ringlog-native"));
            require(exported.file().toString().endsWith(".zip")
                            && Files.isRegularFile(exported.file()),
                    "Physical event/unassigned photos must force a native ZIP");
            require(exported.binaryCount() == 10,
                    "The ZIP must contain one event photo and nine unassigned photos");

            Set<String> sourceConflictEvidence = rows(source, """
                    SELECT stable_key || '|' || conflict_key || '|' || status || '|'
                           || COALESCE(resolution_type, '<null>') || '|'
                           || COALESCE(resolution_value, '<null>') || '|'
                           || COALESCE(canonical_value, '<null>') || '|'
                           || COALESCE(alternative_value, '<null>') || '|'
                           || COALESCE(canonical_snapshot, '<null>') || '|'
                           || COALESCE(alternative_snapshot, '<null>') || '|'
                           || COALESCE(original_note, '<null>')
                    FROM migration_conflict
                    """);

            try (ImportPlan nativePlan = new ImportCoordinator().analyze(exported.file())) {
                require(nativePlan.profile().format() == ImportFormat.RINGLOG_EXPORT_V1,
                        "The native ZIP must be detected exactly as RingLog Export v1");
                require(!nativePlan.requiresConflictReview(),
                        "Native restoration must never launch the conflict wizard");
                require(nativePlan.nativeModel().conflicts().size() == 8,
                        "Pending and resolved conflict state must be staged for restoration");

                long sourceEvents = count(source, "bird_event");
                long sourcePhotos = count(source, "event_photo");
                ImportTransactionService sourceMerge = new ImportTransactionService(
                        source.toString(), sourcePaths
                );
                require(sourceMerge.execute(nativePlan).status()
                                == ImportExecutionResult.Status.APPLIED,
                        "A native export must merge safely over its source database");
                require(count(source, "bird_event") == sourceEvents
                                && count(source, "event_photo") == sourcePhotos,
                        "Native merge must not duplicate existing stable entities");

                ImportTransactionService restore = new ImportTransactionService(
                        target.toString(), targetPaths
                );
                require(restore.execute(nativePlan).status()
                                == ImportExecutionResult.Status.APPLIED,
                        "The first native restore must be applied");
                assertCoreStableKeysEqual(source, target);
                for (String table : List.of(
                        "event_source_alias", "legacy_source_record", "legacy_unassigned_photo",
                        "migration_audit", "migration_conflict", "migration_warning"
                )) {
                    require(stableKeys(source, table).equals(stableKeys(target, table)),
                            "Native restore lost stable state from " + table);
                }
                require(sourceConflictEvidence.equals(rows(target, """
                        SELECT stable_key || '|' || conflict_key || '|' || status || '|'
                               || COALESCE(resolution_type, '<null>') || '|'
                               || COALESCE(resolution_value, '<null>') || '|'
                               || COALESCE(canonical_value, '<null>') || '|'
                               || COALESCE(alternative_value, '<null>') || '|'
                               || COALESCE(canonical_snapshot, '<null>') || '|'
                               || COALESCE(alternative_snapshot, '<null>') || '|'
                               || COALESCE(original_note, '<null>')
                        FROM migration_conflict
                        """)), "Conflict evidence/resolution changed during native restore");
                assertStoredMedia(target, targetPaths, 10);

                long events = count(target, "bird_event");
                long batches = count(target, "import_batch");
                require(restore.execute(nativePlan).status()
                                == ImportExecutionResult.Status.ALREADY_IMPORTED,
                        "The same export_id must be recognized on a second restore");
                require(count(target, "bird_event") == events
                                && count(target, "import_batch") == batches,
                        "A repeated native restore must create no duplicate state");
            }

            ExportResult second = new RingLogExporter(
                    target.toString(), new MediaPathResolver(targetPaths)
            )
                    .exportTo(root.resolve("ringlog-native-second"));
            require(second.file().toString().endsWith(".zip"),
                    "Restored physical state must remain losslessly exportable as ZIP");
            require(stableKeys(source, "bird_event").equals(stableKeys(target, "bird_event")),
                    "export -> import merge -> export must retain event identities");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void mediaFreeStateMayUsePlainXlsx() throws Exception {
        Path root = Files.createTempDirectory("ringlog-native-xlsx-");
        try {
            AppPaths paths = AppPaths.forDataRoot(root.resolve("empty-data"));
            Path database = paths.databasePath();
            Database.initialize(database.toString());
            ExportResult exported = new RingLogExporter(
                    database.toString(), new MediaPathResolver(paths)
            )
                    .exportTo(root.resolve("empty-export"));
            require(exported.file().toString().endsWith(".xlsx")
                            && exported.binaryCount() == 0,
                    "A native export without required binaries may be a plain XLSX");
            require(new WorkbookFormatDetector().detect(exported.file()).format()
                            == ImportFormat.RINGLOG_EXPORT_V1,
                    "The media-free workbook must remain a valid native profile");
        } finally {
            deleteDirectory(root);
        }
    }

    private static void addEventPhoto(Path database, AppPaths paths) throws Exception {
        byte[] content = "native-event-photo".getBytes(StandardCharsets.UTF_8);
        Path file = paths.eventPhotosDirectory().resolve("fixture/event-photo.jpg");
        Files.createDirectories(file.getParent());
        Files.write(file, content);
        try (Connection connection = Database.getConnection(database.toString());
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO event_photo(
                         stable_key, event_id, file_name, file_path, mime_type,
                         source_name, source_reference, content_sha256, content_size,
                         created_at
                     ) VALUES (
                         '77777777-7777-4777-8777-777777777778', 1,
                         'event-photo.jpg', ?, 'image/jpeg', 'TEST', 'TEST:PHOTO:1',
                         ?, ?, '2026-08-26 18:00:00'
                     )
                     """)) {
            statement.setString(1, new MediaPathResolver(paths)
                    .toEventReference(PhotoArea.EVENTS, file));
            statement.setString(2, sha256(content));
            statement.setLong(3, content.length);
            statement.executeUpdate();
        }
    }

    private static void assertCoreStableKeysEqual(Path source, Path target) throws Exception {
        for (String table : List.of("species", "bird", "place", "bird_event", "event_photo")) {
            require(stableKeys(source, table).equals(stableKeys(target, table)),
                    "Stable keys changed in " + table);
        }
    }

    private static void assertStoredMedia(
            Path database,
            AppPaths paths,
            int expected
    ) throws Exception {
        int found = 0;
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement()) {
            for (String table : List.of("event_photo", "legacy_unassigned_photo")) {
                try (ResultSet result = statement.executeQuery(
                        "SELECT file_path, content_sha256, content_size FROM " + table
                )) {
                    while (result.next()) {
                        String reference = result.getString(1);
                        require(!Path.of(reference).isAbsolute(),
                                "Native restoration must keep schema v4 references relative");
                        Path file = "event_photo".equals(table)
                                ? new MediaPathResolver(paths).resolveEventPhoto(reference)
                                : new MediaPathResolver(paths).resolveUnassignedPhoto(reference);
                        require(Files.isRegularFile(file)
                                        && sha256(Files.readAllBytes(file)).equals(result.getString(2))
                                        && Files.size(file) == result.getLong(3),
                                "Restored media bytes/hash/size are not lossless");
                        found++;
                    }
                }
            }
        }
        require(found == expected, "Expected every native binary to be restored");
    }

    private static Set<String> stableKeys(Path database, String table) throws Exception {
        return rows(database, "SELECT stable_key FROM " + table);
    }

    private static Set<String> rows(Path database, String sql) throws Exception {
        Set<String> values = new TreeSet<>();
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }

    private static long scalarLong(Path database, String sql) throws Exception {
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            require(result.next(), "Expected one scalar value");
            return result.getLong(1);
        }
    }

    private static long count(Path database, String table) throws Exception {
        return scalarLong(database, "SELECT COUNT(*) FROM " + table);
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
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
        binaryStateUsesZipAndRestoresTraceabilityWithoutWizard();
        mediaFreeStateMayUsePlainXlsx();
        System.out.println("NativeExportRoundTripTest: PASS");
    }
}
