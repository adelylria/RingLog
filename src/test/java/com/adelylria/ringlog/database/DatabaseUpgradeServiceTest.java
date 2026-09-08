package com.adelylria.ringlog.database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;

import com.adelylria.ringlog.storage.AppPaths;

/** Integration coverage for plan, backup, transaction, and version safety. */
public final class DatabaseUpgradeServiceTest {

    private DatabaseUpgradeServiceTest() {
    }

    public static void fixtureV4ToV5ToV6RunsInOneTransaction() throws Exception {
        Path root = Files.createTempDirectory("ringlog-upgrade-chain-");
        try {
            AppPaths paths = currentDatabase(root);
            DatabaseUpgradeService service = fixtureService(
                    new V4ToV5Migration(false),
                    new V5ToV6Migration(false)
            );

            DatabaseUpgradeResult result = service.upgrade(paths);

            require(result.fromSchema() == 4 && result.targetSchema() == 6,
                    "The result must describe the complete upgrade chain");
            require(result.backup() != null && Files.isRegularFile(result.backup().database()),
                    "A non-empty schema plan must create one pre-upgrade backup");
            require(userVersion(paths.databasePath()) == 6,
                    "The successful SQL chain must publish schema v6");
            require(values(paths.databasePath(), "SELECT step FROM migration_order ORDER BY id")
                            .equals(List.of("4-5", "5-6")),
                    "Fixture migrations must execute in registry order");
            require(userVersion(result.backup().database()) == 4,
                    "The safety backup must retain the original schema");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void failingSecondSqlStepRollsBackTheWholeChain() throws Exception {
        Path root = Files.createTempDirectory("ringlog-upgrade-rollback-");
        try {
            AppPaths paths = currentDatabase(root);
            DatabaseUpgradeService service = fixtureService(
                    new V4ToV5Migration(false),
                    new V5ToV6Migration(true)
            );

            requireUpgradeFailure(() -> service.upgrade(paths),
                    "Failure in the second SQL step must fail the plan");

            require(userVersion(paths.databasePath()) == 4,
                    "A failed SQL chain must restore the original user_version");
            require(!tableExists(paths.databasePath(), "migration_order"),
                    "DDL and rows from every SQL step must roll back together");
            require(countFiles(paths.backupsDirectory(), ".db") == 1,
                    "The validated pre-upgrade backup must survive a failed migration");
            require(countFiles(paths.backupsDirectory(), ".json") == 1,
                    "The matching technical manifest must survive a failed migration");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void currentSchemaCreatesNoBackup() throws Exception {
        Path root = Files.createTempDirectory("ringlog-upgrade-current-");
        try {
            AppPaths paths = currentDatabase(root);

            DatabaseUpgradeResult result = new DatabaseUpgradeService().upgrade(paths);

            require(result.steps().isEmpty() && result.backup() == null,
                    "Current-schema startup must return without a backup");
            require(!Files.exists(paths.backupsDirectory())
                            || countFiles(paths.backupsDirectory(), ".db") == 0,
                    "Normal startup must create zero schema backups");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void futureSchemaIsRejectedBeforeBackup() throws Exception {
        Path root = Files.createTempDirectory("ringlog-upgrade-future-");
        try {
            AppPaths paths = currentDatabase(root);
            try (Connection connection = Database.getConnection(paths.databasePath().toString());
                 Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = 7");
            }

            try {
                fixtureService(new V4ToV5Migration(false), new V5ToV6Migration(false))
                        .upgrade(paths);
                throw new AssertionError("A future schema must be rejected");
            } catch (FutureSchemaVersionException expected) {
                require(expected.detectedSchema() == 7 && expected.supportedSchema() == 6,
                        "The future-schema error must retain detected and supported versions");
            }
            require(!Files.exists(paths.backupsDirectory())
                            || countFiles(paths.backupsDirectory(), ".db") == 0,
                    "A future schema must be rejected before backup creation");
        } finally {
            deleteDirectory(root);
        }
    }

    private static DatabaseUpgradeService fixtureService(
            SchemaMigration first,
            SchemaMigration second
    ) {
        return new DatabaseUpgradeService(
                6,
                new SchemaMigrationRegistry(List.of(first, second)),
                new SchemaBackupService(),
                new DatabaseIntegrityValidator(),
                "1.0-test"
        );
    }

    private static AppPaths currentDatabase(Path root) throws Exception {
        AppPaths paths = AppPaths.forDataRoot(root.resolve("appdata"));
        Files.createDirectories(paths.databasePath().getParent());
        Database.initialize(paths.databasePath().toString());
        return paths;
    }

    private static int userVersion(Path database) throws Exception {
        try (Connection connection = Database.getReadOnlyConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            require(result.next(), "Expected PRAGMA user_version");
            return result.getInt(1);
        }
    }

    private static boolean tableExists(Path database, String table) throws Exception {
        try (Connection connection = Database.getReadOnlyConnection(database.toString());
             var statement = connection.prepareStatement("""
                     SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?
                     """)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static List<String> values(Path database, String query) throws Exception {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        try (Connection connection = Database.getReadOnlyConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(query)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return List.copyOf(values);
    }

    private static long countFiles(Path directory, String suffix) throws Exception {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(suffix)).count();
        }
    }

    private static void requireUpgradeFailure(ThrowingRunnable action, String message)
            throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (DatabaseUpgradeException expected) {
            // Expected rollback path.
        }
    }

    private static void deleteDirectory(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
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
        fixtureV4ToV5ToV6RunsInOneTransaction();
        failingSecondSqlStepRollsBackTheWholeChain();
        currentSchemaCreatesNoBackup();
        futureSchemaIsRejectedBeforeBackup();
        System.out.println("DatabaseUpgradeServiceTest: PASS");
    }

    private record V4ToV5Migration(boolean fail) implements SqlTransactionalMigration {
        @Override
        public int fromSchema() {
            return 4;
        }

        @Override
        public int targetSchema() {
            return 5;
        }

        @Override
        public void migrate(Connection connection) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE migration_order (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            step TEXT NOT NULL
                        )
                        """);
                statement.executeUpdate("INSERT INTO migration_order(step) VALUES ('4-5')");
            }
            if (fail) {
                throw new SQLException("fixture v4 -> v5 failure");
            }
        }
    }

    private record V5ToV6Migration(boolean fail) implements SqlTransactionalMigration {
        @Override
        public int fromSchema() {
            return 5;
        }

        @Override
        public int targetSchema() {
            return 6;
        }

        @Override
        public void migrate(Connection connection) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO migration_order(step) VALUES ('5-6')");
            }
            if (fail) {
                throw new SQLException("fixture v5 -> v6 failure");
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
