package com.adelylria.ringlog.database;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.adelylria.ringlog.storage.AppPaths;

/** Integration tests for validated pre-schema snapshots and their safe manifests. */
public final class SchemaBackupServiceTest {

    private static final Pattern JSON_KEY = Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:");

    private SchemaBackupServiceTest() {
    }

    public static void validBackupHasIntegrityHashAndMinimalManifest() throws Exception {
        Path directory = Files.createTempDirectory("ringlog-schema-backup-");
        try {
            AppPaths paths = AppPaths.forDataRoot(directory);
            Files.createDirectories(paths.databasePath().getParent());
            Database.initialize(paths.databasePath().toString());

            SchemaBackup backup = new SchemaBackupService().create(
                    paths,
                    paths.databasePath(),
                    5,
                    6,
                    "1.0-test"
            );

            require(Files.isRegularFile(backup.database()),
                    "A schema upgrade must publish its validated SQLite backup");
            require(Files.isRegularFile(backup.manifest()),
                    "A validated backup must have a technical manifest");
            require(Files.size(backup.database()) > 0,
                    "The backup database must not be empty");
            require(sha256(backup.database()).equals(backup.sha256()),
                    "The returned SHA-256 must describe the published database bytes");

            try (Connection connection = Database.getReadOnlyConnection(
                    backup.database().toString()
            )) {
                new DatabaseIntegrityValidator().requireValid(connection, 5);
            }

            String manifest = Files.readString(backup.manifest(), StandardCharsets.UTF_8);
            require(jsonKeys(manifest).equals(Set.of(
                            "fromSchema", "targetSchema", "appVersion", "createdAt",
                            "sha256", "databaseFile"
                    )), "The manifest must contain only the six approved technical keys");
            require(manifest.contains("\"sha256\": \"" + backup.sha256() + "\""),
                    "The manifest SHA-256 must match the snapshot");
            require(manifest.contains("\"databaseFile\": \""
                            + backup.database().getFileName() + "\""),
                    "The manifest must reference only the backup file name");
        } finally {
            deleteDirectory(directory);
        }
    }

    public static void invalidSnapshotPublishesNeitherDatabaseNorManifest() throws Exception {
        Path directory = Files.createTempDirectory("ringlog-schema-backup-invalid-");
        try {
            AppPaths paths = AppPaths.forDataRoot(directory);
            Files.createDirectories(paths.databasePath().getParent());
            Files.writeString(paths.databasePath(), "not a sqlite database", StandardCharsets.UTF_8);

            requireFailure(() -> new SchemaBackupService().create(
                    paths,
                    paths.databasePath(),
                    5,
                    6,
                    "1.0-test"
            ), "An invalid SQLite source must not produce a schema backup");

            if (Files.isDirectory(paths.backupsDirectory())) {
                try (var files = Files.list(paths.backupsDirectory())) {
                    require(files.findAny().isEmpty(),
                            "A failed snapshot must leave no database, manifest, or temporary file");
                }
            }
        } finally {
            deleteDirectory(directory);
        }
    }

    private static Set<String> jsonKeys(String json) {
        java.util.HashSet<String> keys = new java.util.HashSet<>();
        Matcher matcher = JSON_KEY.matcher(json);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return Set.copyOf(keys);
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void requireFailure(ThrowingRunnable action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (SchemaBackupException expected) {
            // Expected validation or snapshot failure.
        }
    }

    private static void deleteDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
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
        validBackupHasIntegrityHashAndMinimalManifest();
        invalidSnapshotPublishesNeitherDatabaseNorManifest();
        System.out.println("SchemaBackupServiceTest: PASS");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
