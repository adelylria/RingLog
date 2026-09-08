package com.adelylria.ringlog.importexport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.importexport.service.ImportExecutionResult;
import com.adelylria.ringlog.importexport.service.ImportTransactionService;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.MediaPathResolver;
import com.adelylria.ringlog.testsupport.LegacyV52TestFixture;

/** End-to-end persistence, idempotency, and rollback coverage for legacy v5.2. */
public final class LegacyImportTransactionTest {

    private static final Path REAL_FIXTURE = LegacyV52TestFixture.path();

    private LegacyImportTransactionTest() {
    }

    public static void importsEveryAuthoritativeRowAndIsIdempotent() throws Exception {
        if (LegacyV52TestFixture.skipIfUnavailable("Legacy import transaction integration")) {
            return;
        }
        Path root = Files.createTempDirectory("ringlog-v5-transaction-");
        AppPaths appPaths = AppPaths.forDataRoot(root.resolve("appdata"));
        Path database = appPaths.databasePath();
        try (ImportPlan plan = new ImportCoordinator().analyze(REAL_FIXTURE)) {
            ImportTransactionService service = new ImportTransactionService(
                    database.toString(), appPaths
            );
            ImportExecutionResult first = service.execute(plan);
            require(first.status() == ImportExecutionResult.Status.APPLIED,
                    "The first legacy import must be applied");

            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                require(count(statement, "species") == 4, "Expected all species");
                require(count(statement, "bird") == 271, "Expected all birds");
                require(count(statement, "place") == 4, "Expected all places");
                require(count(statement, "bird_event") == 289, "Expected canonical events only");
                require(count(statement, "legacy_source_record") == 374,
                        "Expected every raw source record");
                require(count(statement, "migration_audit") == 4275,
                        "Expected every audit row");
                require(count(statement, "migration_conflict") == 8,
                        "Expected all structured conflicts");
                require(countWhere(statement, "migration_conflict",
                                "status = 'PENDING_REVIEW'") == 7,
                        "Expected seven pending conflicts");
                require(countWhere(statement, "migration_conflict", "status = 'RESOLVED'") == 1,
                        "Expected the pre-resolved conflict to remain resolved");
                require(countWhere(statement, "bird_event", "review_status = 'REVIEW'") == 7,
                        "Expected seven canonical events under review");
                require(count(statement, "legacy_unassigned_photo") == 9,
                        "Expected nine unassigned photo records");
                require(count(statement, "event_photo") == 0,
                        "Unassigned photos must never become event photos");
                require(count(statement, "event_source_alias")
                                == plan.legacyModel().events().stream()
                                .mapToLong(event -> event.aliases().size()).sum(),
                        "Every declared source alias must be preserved");
                require(count(statement, "import_metadata") == plan.profile().metadata().size(),
                        "Every metadata row must be persisted");
                require(countWhere(statement, "bird_event", "migration_key IS NOT NULL") == 289,
                        "Every legacy event_key must be stored as migration_key");
                try (ResultSet foreignKeys = statement.executeQuery("PRAGMA foreign_key_check")) {
                    require(!foreignKeys.next(), "Imported data must satisfy every FK");
                }
                try (ResultSet photos = statement.executeQuery("""
                        SELECT file_path, content_sha256, content_size
                        FROM legacy_unassigned_photo
                        """)) {
                    int found = 0;
                    while (photos.next()) {
                        String reference = photos.getString(1);
                        require(!Path.of(reference).isAbsolute(),
                                "Schema v4 must store a managed relative reference");
                        Path stored = new MediaPathResolver(appPaths)
                                .resolveUnassignedPhoto(reference);
                        require(Files.isRegularFile(stored),
                                "Every unassigned photo must be copied to persistent storage");
                        require(photos.getString(2).matches("[0-9a-f]{64}")
                                        && photos.getLong(3) == Files.size(stored),
                                "Stored media hash and size must be durable");
                        found++;
                    }
                    require(found == 9, "Expected all persistent unassigned media");
                }
            }

            ImportExecutionResult second = service.execute(plan);
            require(second.status() == ImportExecutionResult.Status.ALREADY_IMPORTED,
                    "The exact same canonical fingerprint must be idempotent");
            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                require(count(statement, "bird_event") == 289
                                && count(statement, "import_batch") == 1,
                        "A repeated legacy import must create no duplicate rows");
            }
        } finally {
            deleteDirectory(root);
        }
    }

    public static void sqlFailureRollsBackDatabaseAndPublishedMedia() throws Exception {
        if (LegacyV52TestFixture.skipIfUnavailable("Legacy import rollback integration")) {
            return;
        }
        Path root = Files.createTempDirectory("ringlog-v5-rollback-");
        AppPaths appPaths = AppPaths.forDataRoot(root.resolve("appdata"));
        Path database = appPaths.databasePath();
        try {
            Database.initialize(database.toString());
            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TRIGGER force_import_failure
                        BEFORE INSERT ON migration_conflict
                        BEGIN
                            SELECT RAISE(ABORT, 'forced import failure');
                        END
                        """);
            }
            try (ImportPlan plan = new ImportCoordinator().analyze(REAL_FIXTURE)) {
                ImportTransactionService service = new ImportTransactionService(
                        database.toString(), appPaths
                );
                requireFailure(() -> service.execute(plan));
            }
            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                for (String table : new String[]{
                        "species", "bird", "place", "bird_event", "event_photo",
                        "import_batch", "legacy_source_record", "migration_audit",
                        "migration_conflict", "legacy_unassigned_photo"
                }) {
                    require(count(statement, table) == 0,
                            "Rollback must leave " + table + " empty");
                }
            }
            long files = Files.exists(appPaths.photosDirectory())
                    ? Files.walk(appPaths.photosDirectory()).filter(Files::isRegularFile).count()
                    : 0;
            if (Files.exists(appPaths.unassignedPhotosDirectory())) {
                files += Files.walk(appPaths.unassignedPhotosDirectory())
                        .filter(Files::isRegularFile).count();
            }
            require(files == 0, "Rollback must delete only media published by the failed import");
        } finally {
            deleteDirectory(root);
        }
    }

    private static long count(Statement statement, String table) throws Exception {
        return countWhere(statement, table, "1 = 1");
    }

    private static long countWhere(Statement statement, String table, String condition)
            throws Exception {
        try (ResultSet result = statement.executeQuery(
                "SELECT COUNT(*) FROM " + table + " WHERE " + condition
        )) {
            require(result.next(), "Expected count for " + table);
            return result.getLong(1);
        }
    }

    private static void requireFailure(ThrowingAction action) throws Exception {
        try {
            action.run();
        } catch (ImportExecutionException expected) {
            return;
        }
        throw new AssertionError("Expected the forced import to fail");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
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

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        importsEveryAuthoritativeRowAndIsIdempotent();
        sqlFailureRollsBackDatabaseAndPublishedMedia();
        System.out.println("LegacyImportTransactionTest: PASS");
    }
}
