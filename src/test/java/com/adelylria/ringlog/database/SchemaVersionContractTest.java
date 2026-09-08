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

    public static void freshDatabaseUsesSchemaV4() throws Exception {
        Path root = Files.createTempDirectory("ringlog-schema-v4-fresh-");
        try {
            Path database = root.resolve("data/ringlog.db");
            Database.initialize(database.toString());

            require(userVersion(database) == 4,
                    "A fresh RingLog database must advertise managed-media schema v4");
            require(Database.SCHEMA_VERSION == 4,
                    "The shared current schema constant must be v4");
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

            DatabaseUpgradeService.Inspection inspection = new DatabaseUpgradeService()
                    .inspect(database, new MediaPathResolver(AppPaths.forDataRoot(root)));
            require(inspection.state() == DatabaseUpgradeService.SchemaState.READY,
                    "Schema v4 must accept canonical managed media references");

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
        freshDatabaseUsesSchemaV4();
        schemaV3UsesLegacyMediaSemantics();
        v3DatabaseIsRecognizedAsNeedingMediaMigration();
        schemaV4UsesManagedMediaReferences();
        mediaReferencesAreOnlyResolvedThroughMediaPathResolver();
        System.out.println("SchemaVersionContractTest: PASS");
    }
}
