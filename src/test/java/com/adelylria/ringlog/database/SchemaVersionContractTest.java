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

    public static void freshDatabaseUsesSchemaV8() throws Exception {
        Path root = Files.createTempDirectory("ringlog-schema-v8-fresh-");
        try {
            Path database = root.resolve("data/ringlog.db");
            Database.initialize(database.toString());

            require(userVersion(database) == 8,
                    "A fresh RingLog database must advertise environment-catalog schema v8");
            require(Database.SCHEMA_VERSION == 8,
                    "The shared current schema constant must be v8");
            require(hasColumn(database, "place", "autonomous_community")
                            && hasColumn(database, "place", "country"),
                    "A fresh database must include both administrative place fields");
            require(hasColumn(database, "bird_event", "temperature_celsius"),
                    "A fresh database must include independent optional ambient temperature");
            require(hasColumn(database, "environment_option", "sort_order"),
                    "A fresh database must include the ordered environment catalog");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void v6TemperatureUpgradeBacksUpAndPreservesHistoricalData() throws Exception {
        Path root = Files.createTempDirectory("ringlog-temperature-v6-to-v7-");
        try {
            AppPaths paths = AppPaths.forDataRoot(root);
            Path database = paths.databasePath();
            Database.initialize(database.toString());
            insertPhoto(database, "events/history/photo.jpg");
            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE bird_event DROP COLUMN temperature_celsius");
                statement.execute("DROP TABLE environment_option");
                statement.execute("PRAGMA user_version = 6");
                statement.execute("""
                        UPDATE bird_event SET thermal_sensation = '22° histórico',
                            observations = 'Conservar notas', capture_method_id = 1
                        """);
            }
            DatabaseUpgradeResult upgraded = new DatabaseUpgradeService().upgrade(paths);
            require(upgraded.fromSchema() == 6 && upgraded.targetSchema() == 8
                            && upgraded.steps().size() == 2,
                    "A real schema-v6 diary must take the additive temperature and environment migrations");
            require(upgraded.backup() != null && userVersion(upgraded.backup().database()) == 6
                            && !hasColumn(upgraded.backup().database(), "bird_event", "temperature_celsius"),
                    "The validated pre-upgrade backup must retain the original schema-v6 structure");
            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery("""
                         SELECT id, stable_key, thermal_sensation, observations, capture_method_id,
                                temperature_celsius FROM bird_event
                         """)) {
                require(row.next() && row.getLong("id") == 1
                                && "00000000-0000-0000-0000-000000000003".equals(row.getString("stable_key"))
                                && "22° histórico".equals(row.getString("thermal_sensation"))
                                && "Conservar notas".equals(row.getString("observations"))
                                && row.getLong("capture_method_id") == 1
                                && row.getObject("temperature_celsius") == null,
                        "Migration must preserve historical identity, notes, capture method and sensation with temperature NULL");
            }
            require("events/history/photo.jpg".equals(photoPath(database)),
                    "The additive temperature migration must preserve photographs");
            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE bird_event SET temperature_celsius = -99");
                V6ToV7TemperatureMigration migration = new V6ToV7TemperatureMigration();
                migration.migrate(connection);
                migration.validate(connection);
                for (String invalid : java.util.List.of("-100", "100", "1.5", "'invalid'")) {
                    requireSqlFailure(() -> statement.executeUpdate(
                                    "UPDATE bird_event SET temperature_celsius = " + invalid),
                            "The migrated temperature column must enforce the same integer range as a fresh database");
                }
                try (ResultSet row = statement.executeQuery("SELECT temperature_celsius FROM bird_event")) {
                    require(row.next() && row.getInt(1) == -99,
                            "Repeating the additive migration and rejecting invalid writes must preserve a valid temperature");
                }
            }
            Database.validate(database.toString());
        } finally {
            deleteDirectory(root);
        }
    }

    public static void v7EnvironmentUpgradePreservesHistoricalTextAndTemperature() throws Exception {
        Path root = Files.createTempDirectory("ringlog-environment-v7-to-v8-");
        try {
            AppPaths paths = AppPaths.forDataRoot(root);
            Path database = paths.databasePath();
            Database.initialize(database.toString());
            insertPhoto(database, "events/history/environment.jpg");
            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE environment_option");
                statement.execute("PRAGMA user_version = 7");
                statement.execute("""
                        UPDATE bird_event SET clouds = 'Sí antiguo', rain = 'No histórico',
                            thermal_sensation = '22° histórico', wind = 'Ligero', temperature_celsius = -5,
                            observations = 'No reescribir', capture_method_id = 1
                        """);
            }
            DatabaseUpgradeResult upgraded = new DatabaseUpgradeService().upgrade(paths);
            require(upgraded.fromSchema() == 7 && upgraded.targetSchema() == 8 && upgraded.steps().size() == 1,
                    "Schema-v7 diaries require exactly the additive environment catalog migration");
            require(upgraded.backup() != null && userVersion(upgraded.backup().database()) == 7
                            && !hasColumn(upgraded.backup().database(), "environment_option", "id"),
                    "The validated pre-upgrade backup must retain the original schema-v7 diary");
            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery("""
                         SELECT id, stable_key, clouds, rain, thermal_sensation, wind, temperature_celsius,
                                observations, capture_method_id FROM bird_event
                         """)) {
                require(row.next() && row.getLong("id") == 1
                                && "00000000-0000-0000-0000-000000000003".equals(row.getString("stable_key"))
                                && "Sí antiguo".equals(row.getString("clouds"))
                                && "No histórico".equals(row.getString("rain"))
                                && "22° histórico".equals(row.getString("thermal_sensation"))
                                && "Ligero".equals(row.getString("wind"))
                                && row.getInt("temperature_celsius") == -5
                                && "No reescribir".equals(row.getString("observations"))
                                && row.getLong("capture_method_id") == 1,
                        "Catalog creation must preserve historical values, identifiers, temperature, notes and capture method exactly");
            }
            require("events/history/environment.jpg".equals(photoPath(database)),
                    "Catalog creation must preserve photograph references");
            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE environment_option SET active = 0 WHERE name = 'Nula'");
                statement.executeUpdate("DELETE FROM environment_option WHERE category = 'RAIN' AND name = 'Débil'");
                new V7ToV8EnvironmentMigration().migrate(connection);
                new V7ToV8EnvironmentMigration().validate(connection);
                try (ResultSet row = statement.executeQuery("SELECT COUNT(*), SUM(active) FROM environment_option")) {
                    require(row.next() && row.getInt(1) == 17 && row.getInt(2) == 16,
                            "Repeating migration must not reinsert removed seeds or reactivate hidden options");
                }
            }
            Database.initialize(database.toString());
            Database.validate(database.toString());
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

            setUserVersion(database, Database.SCHEMA_VERSION);

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

    public static void v5CaptureMethodMigrationPreservesHistoricalEvents() throws Exception {
        Path database = Files.createTempFile("ringlog-method-v5-to-v6-", ".db");
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE bird_event (
                        id INTEGER PRIMARY KEY,
                        stable_key TEXT NOT NULL,
                        capture_type TEXT
                    )
                    """);
            statement.execute("""
                    INSERT INTO bird_event(id, stable_key, capture_type)
                    VALUES (7, '50000000-0000-0000-0000-000000000007', 'RECAPTURE')
                    """);
            statement.execute("PRAGMA user_version = 5");
            connection.setAutoCommit(false);
            V5ToV6CaptureMethodMigration migration = new V5ToV6CaptureMethodMigration();
            migration.migrate(connection);
            migration.validate(connection);
            statement.execute("PRAGMA user_version = 6");
            connection.commit();
            require(userVersion(database) == 6, "The capture-method migration must finish at schema v6");
            try (ResultSet row = statement.executeQuery("SELECT id, stable_key, capture_type, capture_method_id FROM bird_event")) {
                require(row.next() && row.getLong("id") == 7
                                && "50000000-0000-0000-0000-000000000007".equals(row.getString("stable_key"))
                                && "RECAPTURE".equals(row.getString("capture_type"))
                                && row.getObject("capture_method_id") == null,
                        "Migration must preserve identity and classification without inventing a capture method");
            }
            try (ResultSet rows = statement.executeQuery("SELECT stable_key, name FROM capture_method ORDER BY name")) {
                require(rows.next() && "FILATS".equals(rows.getString("name")), "FILATS must be seeded");
                java.util.UUID.fromString(rows.getString("stable_key"));
                require(rows.next() && "RED JAPONESA".equals(rows.getString("name")), "RED JAPONESA must be seeded");
                java.util.UUID.fromString(rows.getString("stable_key"));
                require(!rows.next(), "Migration must create exactly the two requested initial methods");
            }
            require(hasColumn(database, "bird_event", "capture_method_id"), "The event must reference the new catalog");
            try (ResultSet violations = statement.executeQuery("PRAGMA foreign_key_check")) {
                require(!violations.next(), "Capture-method migration must preserve foreign-key integrity");
            }
        } finally {
            Files.deleteIfExists(database);
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
        freshDatabaseUsesSchemaV8();
        v7EnvironmentUpgradePreservesHistoricalTextAndTemperature();
        v6TemperatureUpgradeBacksUpAndPreservesHistoricalData();
        v5CaptureMethodMigrationPreservesHistoricalEvents();
        schemaV3UsesLegacyMediaSemantics();
        v3DatabaseIsRecognizedAsNeedingMediaMigration();
        schemaV4UsesManagedMediaReferences();
        v4PlaceRegionMigrationPreservesExistingPlaces();
        mediaReferencesAreOnlyResolvedThroughMediaPathResolver();
        System.out.println("SchemaVersionContractTest: PASS");
    }
}
