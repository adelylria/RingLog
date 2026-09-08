package com.adelylria.ringlog.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import com.adelylria.ringlog.database.Database;

public final class LegacyDataLocationDetectorTest {

    private LegacyDataLocationDetectorTest() {
    }

    public static void oldDatabaseIsDetected() throws Exception {
        Path root = Files.createTempDirectory("ringlog-detect-old-");
        try {
            Path working = root.resolve("working");
            Files.createDirectories(working);
            Database.initialize(working.resolve("ringlog.db").toString());
            AppPaths managed = AppPaths.forDataRoot(root.resolve("managed"));

            DataLocationAssessment result = new LegacyDataLocationDetector(working, managed)
                    .detect();

            require(result.state() == DataLocationState.LEGACY_ONLY,
                    "A known user.dir database must be detected as legacy-only");
            require(result.legacyDatabase().path().equals(
                            working.resolve("ringlog.db").toAbsolutePath().normalize()),
                    "Detection must report the exact known legacy path");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void existingAppDataDatabaseIsUsed() throws Exception {
        Path root = Files.createTempDirectory("ringlog-detect-managed-");
        try {
            Path working = Files.createDirectories(root.resolve("working"));
            AppPaths managed = AppPaths.forDataRoot(root.resolve("managed"));
            Database.initialize(managed.databasePath().toString());

            DataLocationAssessment result = new LegacyDataLocationDetector(working, managed)
                    .detect();

            require(result.state() == DataLocationState.MANAGED_ONLY,
                    "An AppData database must be selected when no legacy database exists");
            require(result.managedDatabase().path().equals(managed.databasePath()),
                    "Detection must retain the managed AppPaths database");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void identicalOldAndNewDatabaseUsesAppData() throws Exception {
        Path root = Files.createTempDirectory("ringlog-detect-identical-");
        try {
            Path working = Files.createDirectories(root.resolve("working"));
            Path oldDatabase = working.resolve("ringlog.db");
            Database.initialize(oldDatabase.toString());
            AppPaths managed = AppPaths.forDataRoot(root.resolve("managed"));
            Files.createDirectories(managed.databasePath().getParent());
            Files.copy(oldDatabase, managed.databasePath());

            DataLocationAssessment result = new LegacyDataLocationDetector(working, managed)
                    .detect();

            require(result.state() == DataLocationState.BOTH_IDENTICAL,
                    "Byte-identical known databases must be recognized safely");
            require(result.preferredDatabase().equals(managed.databasePath()),
                    "The managed database must be preferred without removing the old copy");
        } finally {
            deleteDirectory(root);
        }
    }

    public static void differentOldAndNewDatabaseRequiresUserDecision() throws Exception {
        Path root = Files.createTempDirectory("ringlog-detect-different-");
        try {
            Path working = Files.createDirectories(root.resolve("working"));
            Path oldDatabase = working.resolve("ringlog.db");
            Database.initialize(oldDatabase.toString());
            AppPaths managed = AppPaths.forDataRoot(root.resolve("managed"));
            Database.initialize(managed.databasePath().toString());
            try (var connection = Database.getConnection(oldDatabase.toString());
                 var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO species(stable_key, scientific_name)
                        VALUES ('30000000-0000-0000-0000-000000000001', 'Different')
                        """);
            }

            DataLocationAssessment result = new LegacyDataLocationDetector(working, managed)
                    .detect();

            require(result.state() == DataLocationState.BOTH_DIFFERENT,
                    "Different databases must never be selected or merged automatically");
            require(result.preferredDatabase() == null,
                    "A conflicting installation must require an explicit user decision");
        } finally {
            deleteDirectory(root);
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
        oldDatabaseIsDetected();
        existingAppDataDatabaseIsUsed();
        identicalOldAndNewDatabaseUsesAppData();
        differentOldAndNewDatabaseRequiresUserDecision();
        System.out.println("LegacyDataLocationDetectorTest: PASS");
    }
}
