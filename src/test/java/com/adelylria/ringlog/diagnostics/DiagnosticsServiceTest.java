package com.adelylria.ringlog.diagnostics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.storage.AppPaths;

/** Verifies the technical, non-domain diagnostics shown in Settings. */
public final class DiagnosticsServiceTest {

    private DiagnosticsServiceTest() {
    }

    public static void snapshotUsesManagedPathsAndCurrentSchema() throws Exception {
        Path root = Files.createTempDirectory("ringlog-diagnostics-");
        try {
            AppPaths paths = AppPaths.forDataRoot(root.resolve("appdata"));
            Database.initialize(paths.databasePath().toString());
            LoggingInitialization logging = RingLogLogging.initialize(paths);

            DiagnosticsSnapshot snapshot = new DiagnosticsService().snapshot(paths);

            require(snapshot.dataRoot().equals(paths.dataRoot()),
                    "Diagnostics must use the exact managed data root");
            require(snapshot.database().equals(paths.databasePath()),
                    "Diagnostics must use the exact managed database path");
            require(snapshot.logFile().equals(logging.logFile()),
                    "Diagnostics must expose the configured log location");
            require(snapshot.schemaVersion() == 5,
                    "Diagnostics must report the actual database schema");
            require(!snapshot.javaVersion().isBlank() && !snapshot.operatingSystem().isBlank(),
                    "Runtime diagnostics must identify Java and the operating system");
        } finally {
            RingLogLogging.shutdown();
            deleteDirectory(root);
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
        snapshotUsesManagedPathsAndCurrentSchema();
        System.out.println("DiagnosticsServiceTest: PASS");
    }
}
