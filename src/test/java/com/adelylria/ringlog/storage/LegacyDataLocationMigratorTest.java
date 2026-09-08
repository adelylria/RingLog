package com.adelylria.ringlog.storage;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.database.StagedStorageMigrationContext;
import com.adelylria.ringlog.database.V3ToV4StorageMigration;

public final class LegacyDataLocationMigratorTest {

    private LegacyDataLocationMigratorTest() {
    }

    public static void successfulLocationMigrationEndsAtSchemaV4() throws Exception {
        Path root = Files.createTempDirectory("ringlog-location-success-");
        try {
            Fixture fixture = legacyV3Fixture(root, true, true);
            String databaseBefore = sha256(fixture.database());
            String photoBefore = sha256(fixture.photo());
            AppPaths target = AppPaths.forDataRoot(root.resolve("appdata"));

            MigrationResult result = new LegacyDataLocationMigrator()
                    .migrate(fixture.database(), target, MigrationProgressListener.NONE);

            require(result.status() == MigrationStatus.COMPLETED,
                    "A complete v3 fixture must migrate successfully");
            require(userVersion(target.databasePath()) == 4,
                    "A successful location migration must end at schema v4");
            require(sha256(fixture.database()).equals(databaseBefore),
                    "The original v3 database must remain byte-for-byte intact");
            require(sha256(fixture.photo()).equals(photoBefore),
                    "The original photo must remain byte-for-byte intact");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void absolutePhotoPathMigratesToRelativeReference() throws Exception {
        Path root = Files.createTempDirectory("ringlog-location-relative-");
        try {
            Fixture fixture = legacyV3Fixture(root, true, true);
            AppPaths target = AppPaths.forDataRoot(root.resolve("appdata"));
            MediaPathResolver resolver = new MediaPathResolver(target);

            new LegacyDataLocationMigrator().migrate(
                    fixture.database(), target, MigrationProgressListener.NONE
            );

            String reference = eventPhotoPath(target.databasePath());
            require(reference.startsWith("events/"),
                    "A legacy event photo must become an events managed reference");
            require(!Path.of(reference).isAbsolute() && !reference.contains("\\"),
                    "The v4 database must not retain a physical Windows path");
            Path migrated = resolver.resolveEventPhoto(reference);
            require(Files.isRegularFile(migrated),
                    "The managed event reference must resolve to a published file");
            require(sha256(migrated).equals(sha256(fixture.photo())),
                    "Published media must match the legacy source bytes");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void oldDatabaseMigrationUsesStaging() throws Exception {
        Path root = Files.createTempDirectory("ringlog-location-staging-");
        try {
            Fixture fixture = legacyV3Fixture(root, true, true);
            AppPaths target = AppPaths.forDataRoot(root.resolve("appdata"));
            List<MigrationProgress> observed = new ArrayList<>();

            new LegacyDataLocationMigrator().migrate(fixture.database(), target, progress -> {
                observed.add(progress);
                if (progress.stage() == MigrationStage.SQLITE_SNAPSHOT_CREATED) {
                    require(progress.stagingDatabase().startsWith(target.migrationDirectory()),
                            "SQLite staging must remain below AppPaths.migrationDirectory");
                    require(Files.isRegularFile(progress.stagingDatabase()),
                            "The progress event must expose the real staging snapshot");
                }
            });

            require(observed.stream().anyMatch(progress ->
                            progress.stage() == MigrationStage.SQLITE_SNAPSHOT_CREATED),
                    "The migration must create and report a SQLite staging snapshot");
            require(observed.get(observed.size() - 1).stage() == MigrationStage.DATABASE_PUBLISHED,
                    "The database publication must be the final persistent stage");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void missingPhotoIsReported() throws Exception {
        Path root = Files.createTempDirectory("ringlog-location-missing-");
        try {
            Fixture fixture = legacyV3Fixture(root, false, true);
            AppPaths target = AppPaths.forDataRoot(root.resolve("appdata"));

            MigrationResult result = new LegacyDataLocationMigrator()
                    .migrate(fixture.database(), target, MigrationProgressListener.NONE);

            require(result.status() == MigrationStatus.BLOCKED,
                    "A referenced missing photo must block the first migration implementation");
            require(result.issues().size() == 1
                            && result.issues().get(0).mediaStatus() == MediaStatus.MISSING,
                    "The result must preserve an explicit future-relocatable MISSING issue");
            require(result.issues().get(0).originalReference()
                            .equals(fixture.photo().toString()),
                    "The missing issue must retain the original reference unchanged");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void failedMediaMigrationNeverProducesSchemaV4Database() throws Exception {
        Path root = Files.createTempDirectory("ringlog-location-no-v4-");
        try {
            Fixture fixture = legacyV3Fixture(root, false, true);
            AppPaths target = AppPaths.forDataRoot(root.resolve("appdata"));

            new LegacyDataLocationMigrator().migrate(
                    fixture.database(), target, MigrationProgressListener.NONE
            );

            require(!Files.exists(target.databasePath()),
                    "A failed media migration must not publish the destination database");
            require(userVersion(fixture.database()) == 3,
                    "A blocked migration must not mark the source database as v4");
            try (var paths = Files.walk(target.dataRoot())) {
                for (Path candidate : paths.filter(path ->
                                Files.isRegularFile(path)
                                        && path.getFileName().toString().endsWith(".db"))
                        .toList()) {
                    require(userVersion(candidate) != 4,
                            "No failed staging database may advertise schema v4");
                }
            }
        } finally {
            deleteDirectory(root);
        }
    }

    public static void oldDatabaseRemainsAfterSuccessfulMigration() throws Exception {
        Path root = Files.createTempDirectory("ringlog-location-source-remains-");
        try {
            Fixture fixture = legacyV3Fixture(root, true, true);
            AppPaths target = AppPaths.forDataRoot(root.resolve("appdata"));

            new LegacyDataLocationMigrator().migrate(
                    fixture.database(), target, MigrationProgressListener.NONE
            );

            require(Files.isRegularFile(fixture.database()),
                    "RingLog must never remove the legacy database");
            require(Files.isRegularFile(fixture.photo()),
                    "RingLog must never remove legacy media");
            require(Files.isRegularFile(target.databasePath()),
                    "The managed database must be a separate published file");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void failedMigrationLeavesOldDatabaseUntouched() throws Exception {
        Path root = Files.createTempDirectory("ringlog-location-failed-source-");
        try {
            Fixture fixture = legacyV3Fixture(root, false, true);
            String databaseBefore = sha256(fixture.database());
            AppPaths target = AppPaths.forDataRoot(root.resolve("appdata"));

            MigrationResult result = new LegacyDataLocationMigrator().migrate(
                    fixture.database(), target, MigrationProgressListener.NONE
            );

            require(result.status() == MigrationStatus.BLOCKED,
                    "A missing source photo must block migration");
            require(sha256(fixture.database()).equals(databaseBefore),
                    "A blocked migration must leave the old DB byte-for-byte untouched");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void unassignedPhotosSurviveLocationMigration() throws Exception {
        Path root = Files.createTempDirectory("ringlog-location-unassigned-");
        try {
            Fixture fixture = legacyV3Fixture(root, true, true);
            insertUnassignedPhoto(fixture.database(), fixture.photo());
            AppPaths target = AppPaths.forDataRoot(root.resolve("appdata"));

            new LegacyDataLocationMigrator().migrate(
                    fixture.database(), target, MigrationProgressListener.NONE
            );

            String reference = unassignedPhotoPath(target.databasePath());
            require(reference != null && !reference.startsWith("/") && !reference.contains(":"),
                    "Unassigned media must be stored as a managed relative reference");
            require(Files.isRegularFile(
                            new MediaPathResolver(target).resolveUnassignedPhoto(reference)),
                    "The unassigned reference must resolve after publication");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void managedV3UsesTheSharedStagedMigration() throws Exception {
        Path root = Files.createTempDirectory("ringlog-managed-v3-");
        try {
            AppPaths paths = AppPaths.forDataRoot(root.resolve("appdata"));
            Files.createDirectories(paths.databasePath().getParent());
            Path externalPhoto = root.resolve("external-photo.jpg");
            Files.write(externalPhoto, new byte[]{91, 82, 73, 64});
            String photoBefore = sha256(externalPhoto);
            Database.initialize(paths.databasePath().toString());
            try (Connection connection = Database.getConnection(paths.databasePath().toString());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO species(stable_key, scientific_name)
                        VALUES ('30000000-0000-0000-0000-000000000001', 'Managed species')
                        """);
                statement.executeUpdate("""
                        INSERT INTO bird(stable_key, ring_number, species_id)
                        VALUES ('30000000-0000-0000-0000-000000000002', 'MANAGED-001', 1)
                        """);
                statement.executeUpdate("""
                        INSERT INTO bird_event(stable_key, bird_id, event_type, event_date)
                        VALUES ('30000000-0000-0000-0000-000000000003', 1,
                                'RINGING', '2026-08-30')
                        """);
                try (var insert = connection.prepareStatement("""
                        INSERT INTO event_photo(stable_key, event_id, file_name, file_path)
                        VALUES ('30000000-0000-0000-0000-000000000004', 1,
                                'external-photo.jpg', ?)
                        """)) {
                    insert.setString(1, externalPhoto.toAbsolutePath().normalize().toString());
                    insert.executeUpdate();
                }
                statement.execute("PRAGMA user_version = 3");
            }

            MigrationResult result = new V3ToV4StorageMigration().migrate(
                    new StagedStorageMigrationContext(paths, MigrationProgressListener.NONE)
            );

            require(result.status() == MigrationStatus.COMPLETED,
                    "A managed v3 database must complete the shared staged migration");
            require(userVersion(paths.databasePath()) == 4,
                    "The managed database must only advertise v4 after publication");
            String reference = eventPhotoPath(paths.databasePath());
            require(reference.startsWith("events/") && !Path.of(reference).isAbsolute(),
                    "Managed v3 absolute paths must become relative v4 references");
            require(Files.isRegularFile(new MediaPathResolver(paths).resolveEventPhoto(reference)),
                    "The converted reference must resolve in the final managed media root");
            require(sha256(externalPhoto).equals(photoBefore),
                    "The external source photo must remain byte-for-byte intact");
        } finally {
            deleteDirectory(root);
        }
    }

    private static Fixture legacyV3Fixture(
            Path root,
            boolean createPhoto,
            boolean absoluteReference
    ) throws Exception {
        Path legacy = root.resolve("legacy");
        Files.createDirectories(legacy);
        Path photo = legacy.resolve("legacy-photo.jpg");
        if (createPhoto) {
            Files.write(photo, new byte[]{11, 22, 33, 44, 55, 66});
        }
        Path database = legacy.resolve("ringlog.db");
        Database.initialize(database.toString());
        String reference = absoluteReference
                ? photo.toAbsolutePath().normalize().toString()
                : photo.getFileName().toString();
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO species(stable_key, scientific_name)
                    VALUES ('10000000-0000-0000-0000-000000000001', 'Legacy species')
                    """);
            statement.executeUpdate("""
                    INSERT INTO bird(stable_key, ring_number, species_id)
                    VALUES ('10000000-0000-0000-0000-000000000002', 'LEGACY-001', 1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO bird_event(stable_key, bird_id, event_type, event_date)
                    VALUES ('10000000-0000-0000-0000-000000000003', 1, 'RINGING', '2025-01-02')
                    """);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO event_photo(stable_key, event_id, file_name, file_path)
                    VALUES ('10000000-0000-0000-0000-000000000004', 1, 'legacy-photo.jpg', ?)
                    """)) {
                insert.setString(1, reference);
                insert.executeUpdate();
            }
            statement.execute("PRAGMA user_version = 3");
        }
        return new Fixture(database, photo);
    }

    private static void insertUnassignedPhoto(Path database, Path photo) throws Exception {
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO import_batch(
                        stable_key, source_format, format_version, source_name,
                        source_reference, fingerprint, import_mode, imported_at
                    ) VALUES (
                        '20000000-0000-0000-0000-000000000001', 'LEGACY_MIGRATION', '5',
                        'fixture', 'fixture:1', 'fixture-fingerprint', 'LEGACY_MIGRATION',
                        '2026-08-30T00:00:00Z'
                    )
                    """);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO legacy_unassigned_photo(
                        stable_key, import_batch_id, source_reference, file_name, file_path
                    ) VALUES (
                        '20000000-0000-0000-0000-000000000002', 1,
                        'fixture:photo', 'legacy-photo.jpg', ?
                    )
                    """)) {
                insert.setString(1, photo.toAbsolutePath().normalize().toString());
                insert.executeUpdate();
            }
        }
    }

    private static String eventPhotoPath(Path database) throws Exception {
        return singleString(database, "SELECT file_path FROM event_photo");
    }

    private static String unassignedPhotoPath(Path database) throws Exception {
        return singleString(database, "SELECT file_path FROM legacy_unassigned_photo");
    }

    private static String singleString(Path database, String sql) throws Exception {
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            require(result.next(), "Expected one fixture row for " + sql);
            return result.getString(1);
        }
    }

    private static int userVersion(Path database) throws Exception {
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            require(result.next(), "Expected PRAGMA user_version row");
            return result.getInt(1);
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
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

    private record Fixture(Path database, Path photo) {
    }

    public static void main(String[] args) throws Exception {
        successfulLocationMigrationEndsAtSchemaV4();
        absolutePhotoPathMigratesToRelativeReference();
        oldDatabaseMigrationUsesStaging();
        missingPhotoIsReported();
        failedMediaMigrationNeverProducesSchemaV4Database();
        oldDatabaseRemainsAfterSuccessfulMigration();
        failedMigrationLeavesOldDatabaseUntouched();
        unassignedPhotosSurviveLocationMigration();
        managedV3UsesTheSharedStagedMigration();
        System.out.println("LegacyDataLocationMigratorTest: PASS");
    }
}
