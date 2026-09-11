package com.adelylria.ringlog.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;

import com.adelylria.ringlog.database.Database;

public final class ApplicationStorageBootstrapTest {

    private ApplicationStorageBootstrapTest() {
    }

    public static void freshInstallCreatesDatabaseInAppData() throws Exception {
        Path root = Files.createTempDirectory("ringlog-bootstrap-fresh-");
        try {
            Path working = Files.createDirectories(root.resolve("working"));
            AppPaths paths = AppPaths.forDataRoot(root.resolve("local-app-data/RingLog"));
            ApplicationStorageBootstrap bootstrap = new ApplicationStorageBootstrap(
                    paths, new LegacyDataLocationDetector(working, paths)
            );

            StartupStorageResult result = bootstrap.prepare();

            require(result.status() == StartupStorageStatus.READY,
                    "A fresh install must become ready without a migration screen");
            require(result.database().equals(paths.databasePath()),
                    "A fresh install must use AppPaths.databasePath");
            require(Files.isRegularFile(paths.databasePath()) && userVersion(paths.databasePath()) == 5,
                    "A fresh install must create schema v5 directly in AppData");
            require(!Files.exists(working.resolve("ringlog.db")),
                    "Fresh startup must not recreate ringlog.db in user.dir");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void managedStartupCreatesApplicationDirectories() throws Exception {
        Path root = Files.createTempDirectory("ringlog-bootstrap-managed-dirs-");
        try {
            Path working = Files.createDirectories(root.resolve("working"));
            AppPaths paths = AppPaths.forDataRoot(root.resolve("managed"));
            Files.createDirectories(paths.databasePath().getParent());
            Database.initialize(paths.databasePath().toString());

            StartupStorageResult result = new ApplicationStorageBootstrap(
                    paths, new LegacyDataLocationDetector(working, paths)
            ).prepare();

            require(result.status() == StartupStorageStatus.READY,
                    "An existing managed database must remain ready");
            for (Path directory : new Path[]{
                    paths.eventPhotosDirectory(),
                    paths.nativePhotosDirectory(),
                    paths.unassignedPhotosDirectory(),
                    paths.backupsDirectory(),
                    paths.logsDirectory(),
                    paths.migrationDirectory()
            }) {
                require(Files.isDirectory(directory),
                        "Managed startup must prepare application directory " + directory);
            }
        } finally {
            deleteDirectory(root);
        }
    }

    public static void migrationPublishesOnlyAfterMediaValidation() throws Exception {
        Path root = Files.createTempDirectory("ringlog-bootstrap-order-");
        try {
            Path working = Files.createDirectories(root.resolve("working"));
            Path oldDatabase = working.resolve("ringlog.db");
            Database.initialize(oldDatabase.toString());
            try (Connection connection = Database.getConnection(oldDatabase.toString());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO species(stable_key, scientific_name)
                        VALUES ('40000000-0000-0000-0000-000000000001', 'Fixture')
                        """);
                statement.execute("PRAGMA user_version = 3");
            }
            AppPaths paths = AppPaths.forDataRoot(root.resolve("managed"));

            new LegacyDataLocationMigrator().migrate(oldDatabase, paths, progress -> {
                if (progress.stage() != MigrationStage.DATABASE_PUBLISHED) {
                    require(!Files.exists(paths.databasePath()),
                            "The managed DB must remain absent before the final publication event");
                }
            });

            require(Files.isRegularFile(paths.databasePath()),
                    "The DB must be visible after every staging validation succeeds");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void migrationValidatesForeignKeys() throws Exception {
        Path root = Files.createTempDirectory("ringlog-bootstrap-fk-");
        try {
            Path oldDatabase = root.resolve("ringlog.db");
            Database.initialize(oldDatabase.toString());
            try (Connection connection = Database.getConnection(oldDatabase.toString());
                 Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = OFF");
                statement.executeUpdate("""
                        INSERT INTO bird_event(stable_key, bird_id, event_type, event_date)
                        VALUES ('40000000-0000-0000-0000-000000000002', 999, 'RINGING', '2025-01-01')
                        """);
                statement.execute("PRAGMA user_version = 3");
            }
            AppPaths paths = AppPaths.forDataRoot(root.resolve("managed"));

            boolean failed = false;
            try {
                new LegacyDataLocationMigrator().migrate(
                        oldDatabase, paths, MigrationProgressListener.NONE
                );
            } catch (java.sql.SQLException expected) {
                failed = true;
            }

            require(failed, "A foreign_key_check failure must block publication");
            require(!Files.exists(paths.databasePath()),
                    "An invalid snapshot must never be published");
            require(userVersion(oldDatabase) == 3,
                    "Validation failure must leave the legacy DB unchanged");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void migrationValidatesQuickCheck() throws Exception {
        Path root = Files.createTempDirectory("ringlog-bootstrap-quick-check-");
        try {
            Path oldDatabase = root.resolve("ringlog.db");
            Database.initialize(oldDatabase.toString());
            try (Connection connection = Database.getConnection(oldDatabase.toString());
                 Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = 3");
            }
            AppPaths paths = AppPaths.forDataRoot(root.resolve("managed"));

            boolean failed = false;
            try {
                new LegacyDataLocationMigrator().migrate(oldDatabase, paths, progress -> {
                    if (progress.stage() == MigrationStage.SQLITE_SNAPSHOT_CREATED) {
                        try {
                            Files.write(
                                    progress.stagingDatabase(),
                                    new byte[128],
                                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
                            );
                        } catch (java.io.IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    }
                });
            } catch (java.sql.SQLException expected) {
                failed = true;
            }

            require(failed, "A snapshot that cannot pass quick_check must block migration");
            require(!Files.exists(paths.databasePath()),
                    "A damaged staging snapshot must never be published");
            require(userVersion(oldDatabase) == 3,
                    "Quick-check failure must leave the source untouched");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void successfulReceiptPreventsRepeatedMigrationPrompt() throws Exception {
        Path root = Files.createTempDirectory("ringlog-bootstrap-receipt-");
        try {
            Path working = Files.createDirectories(root.resolve("working"));
            Path oldDatabase = working.resolve("ringlog.db");
            Database.initialize(oldDatabase.toString());
            try (Connection connection = Database.getConnection(oldDatabase.toString());
                 Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = 3");
            }
            AppPaths paths = AppPaths.forDataRoot(root.resolve("managed"));
            new LegacyDataLocationMigrator().migrate(
                    oldDatabase, paths, MigrationProgressListener.NONE
            );

            StartupStorageResult result = new ApplicationStorageBootstrap(
                    paths, new LegacyDataLocationDetector(working, paths)
            ).prepare();

            require(result.status() == StartupStorageStatus.READY,
                    "A matching receipt must prevent asking to migrate the untouched source again");
            require(Files.isRegularFile(paths.migrationDirectory()
                            .resolve("migration-receipt.json")),
                    "A successful migration must leave a technical JSON receipt");
        } finally {
            deleteDirectory(root);
        }
    }

    private static int userVersion(Path database) throws Exception {
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            require(result.next(), "Expected user_version");
            return result.getInt(1);
        }
    }

    private static void deleteDirectory(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
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
        freshInstallCreatesDatabaseInAppData();
        managedStartupCreatesApplicationDirectories();
        migrationPublishesOnlyAfterMediaValidation();
        migrationValidatesForeignKeys();
        migrationValidatesQuickCheck();
        successfulReceiptPreventsRepeatedMigrationPrompt();
        System.out.println("ApplicationStorageBootstrapTest: PASS");
    }
}
