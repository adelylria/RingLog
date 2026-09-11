package com.adelylria.ringlog.database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;

import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.MediaPathResolver;

public final class SchemaVersionContractTest {

    private SchemaVersionContractTest() {
    }

    public static void freshDatabaseUsesSchemaV5() throws Exception {
        Path root = Files.createTempDirectory("ringlog-schema-v5-fresh-");
        try {
            Path database = root.resolve("data/ringlog.db");
            Database.initialize(database.toString());

            require(userVersion(database) == 5,
                    "A fresh RingLog database must advertise place-region schema v5");
            require(Database.SCHEMA_VERSION == 5,
                    "The shared current schema constant must be v5");
            require(hasColumn(database, "place", "autonomous_community")
                            && hasColumn(database, "place", "country"),
                    "A fresh database must include both administrative place fields");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void schemaV3UsesLegacyMediaSemantics() throws Exception {
        Path root = Files.createTempDirectory("ringlog-schema-v3-media-");
        try {
            Path database = root.resolve("ringlog.db");
            Database.initialize(database.toString());
            insertPhoto(database, "C:\\legacy\\absolute-photo.jpg");
            setUserVersion(database, 3);

            DatabaseUpgradeService.Inspection inspection = new DatabaseUpgradeService()
                    .inspect(database, new MediaPathResolver(AppPaths.forDataRoot(root)));
            require(inspection.state()
                            == DatabaseUpgradeService.SchemaState.MEDIA_MIGRATION_REQUIRED,
                    "Schema v3 must preserve and report legacy physical media paths");
            require("C:\\legacy\\absolute-photo.jpg".equals(photoPath(database)),
                    "Inspecting v3 must never reinterpret or modify its legacy path");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void v3DatabaseIsRecognizedAsNeedingMediaMigration() throws Exception {
        Path root = Files.createTempDirectory("ringlog-schema-v3-detect-");
        try {
            Path database = root.resolve("ringlog.db");
            Database.initialize(database.toString());
            setUserVersion(database, 3);

            DatabaseUpgradeService.Inspection inspection = new DatabaseUpgradeService()
                    .inspect(database, new MediaPathResolver(AppPaths.forDataRoot(root)));
            require(inspection.userVersion() == 3,
                    "The inspection must report the actual source version");
            require(inspection.state()
                            == DatabaseUpgradeService.SchemaState.MEDIA_MIGRATION_REQUIRED,
                    "A v3 database cannot be opened as a current managed-media database");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void schemaV4UsesManagedMediaReferences() throws Exception {
        Path root = Files.createTempDirectory("ringlog-schema-v4-media-");
        try {
            Path database = root.resolve("ringlog.db");
            Database.initialize(database.toString());
            insertPhoto(database, "events/ab/managed-photo.jpg");
            setUserVersion(database, 4);

            DatabaseUpgradeService.Inspection inspection = new DatabaseUpgradeService()
                    .inspect(database, new MediaPathResolver(AppPaths.forDataRoot(root)));
            require(inspection.state() == DatabaseUpgradeService.SchemaState.SQL_UPGRADE_REQUIRED,
                    "Schema v4 must request the additive place-region migration");
            new MediaPathResolver(AppPaths.forDataRoot(root))
                    .resolveEventPhoto("events/ab/managed-photo.jpg");

            setUserVersion(database, 5);

            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "UPDATE event_photo SET file_path = 'C:\\legacy\\photo.jpg'"
                );
            }
            requireSqlFailure(() -> new DatabaseUpgradeService().inspect(
                    database, new MediaPathResolver(AppPaths.forDataRoot(root))
            ), "Schema v4 must reject a physical path that bypasses MediaPathResolver");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void v4PlaceRegionMigrationPreservesExistingPlaces() throws Exception {
        Path database = Files.createTempFile("ringlog-place-v4-to-v5-", ".db");
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE place (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        stable_key TEXT NOT NULL UNIQUE,
                        name TEXT NOT NULL,
                        locality TEXT
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO place(stable_key, name, locality)
                    VALUES ('50000000-0000-0000-0000-000000000001', 'Els Rafals', 'Pollença')
                    """);
            statement.execute("PRAGMA user_version = 4");

            connection.setAutoCommit(false);
            V4ToV5PlaceRegionMigration migration = new V4ToV5PlaceRegionMigration();
            migration.migrate(connection);
            statement.execute("PRAGMA user_version = 5");
            migration.validate(connection);
            connection.commit();

            require(userVersion(database) == 5,
                    "The additive migration must finish at schema v5");
            require(hasColumn(database, "place", "autonomous_community")
                            && hasColumn(database, "place", "country"),
                    "The migration must add both administrative place fields");
            try (ResultSet row = statement.executeQuery("""
                    SELECT stable_key, name, locality, autonomous_community, country
                    FROM place
                    """)) {
                require(row.next()
                                && "50000000-0000-0000-0000-000000000001".equals(row.getString(1))
                                && "Els Rafals".equals(row.getString(2))
                                && "Pollença".equals(row.getString(3))
                                && row.getString(4) == null
                                && row.getString(5) == null,
                        "The migration must preserve the existing place byte-for-byte logically");
            }
        } finally {
            Files.deleteIfExists(database);
        }
    }

    public static void mediaReferencesAreOnlyResolvedThroughMediaPathResolver()
            throws Exception {
        Path root = Files.createTempDirectory("ringlog-schema-v4-resolver-");
        try {
            Path database = root.resolve("ringlog.db");
            Database.initialize(database.toString());
            insertPhoto(database, "../../outside.jpg");

            requireSqlFailure(() -> new DatabaseUpgradeService().inspect(
                    database, new MediaPathResolver(AppPaths.forDataRoot(root))
            ), "Unsafe database references must be rejected by the managed resolver boundary");
        } finally {
            deleteDirectory(root);
        }
    }

    private static void insertPhoto(Path database, String filePath) throws Exception {
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO species(stable_key, scientific_name)
                    VALUES ('00000000-0000-0000-0000-000000000001', 'Species test')
                    """);
            statement.executeUpdate("""
                    INSERT INTO bird(stable_key, ring_number, species_id)
                    VALUES ('00000000-0000-0000-0000-000000000002', 'RING-V4', 1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO bird_event(stable_key, bird_id, event_type, event_date)
                    VALUES ('00000000-0000-0000-0000-000000000003', 1, 'RINGING', '2026-08-30')
                    """);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO event_photo(stable_key, event_id, file_name, file_path)
                    VALUES ('00000000-0000-0000-0000-000000000004', 1, 'photo.jpg', ?)
                    """)) {
                insert.setString(1, filePath);
                insert.executeUpdate();
            }
        }
    }

    private static int userVersion(Path database) throws Exception {
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            require(result.next(), "PRAGMA user_version returned no row");
            return result.getInt(1);
        }
    }

    private static void setUserVersion(Path database, int version) throws Exception {
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = " + version);
        }
    }

    private static String photoPath(Path database) throws Exception {
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT file_path FROM event_photo")) {
            require(result.next(), "Expected one photo fixture");
            return result.getString(1);
        }
    }

    private static boolean hasColumn(Path database, String table, String column)
            throws Exception {
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equals(result.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static void requireSqlFailure(ThrowingAction action, String message)
            throws Exception {
        try {
            action.run();
        } catch (SQLException expected) {
            return;
        }
        throw new AssertionError(message);
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

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        freshDatabaseUsesSchemaV5();
        schemaV3UsesLegacyMediaSemantics();
        v3DatabaseIsRecognizedAsNeedingMediaMigration();
        schemaV4UsesManagedMediaReferences();
        v4PlaceRegionMigrationPreservesExistingPlaces();
        mediaReferencesAreOnlyResolvedThroughMediaPathResolver();
        System.out.println("SchemaVersionContractTest: PASS");
    }
}
