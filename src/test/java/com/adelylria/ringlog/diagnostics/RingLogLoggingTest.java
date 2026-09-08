package com.adelylria.ringlog.diagnostics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.apache.logging.log4j.LogManager;

import com.adelylria.ringlog.storage.AppPaths;

/** File-path, privacy, fallback, and rolling-policy integration tests. */
public final class RingLogLoggingTest {

    private RingLogLoggingTest() {
    }

    public static void persistentLogUsesAppPathsAndRedactsFailureContent() throws Exception {
        Path root = Files.createTempDirectory("ringlog-logging-");
        try {
            AppPaths paths = AppPaths.forDataRoot(root.resolve("appdata"));

            LoggingInitialization result = RingLogLogging.initialize(paths);
            SafeLog.failure(
                    "import",
                    new IllegalStateException(
                            "token=secret-value; observations=private note; "
                                    + "canonical_snapshot=snapshot body"
                    )
            );
            RingLogLogging.shutdown();

            require(result.persistent(), "A writable AppPaths log root must use persistent logging");
            require(result.logFile().equals(paths.logsDirectory().resolve("ringlog.log")),
                    "The log file must live below AppPaths.logsDirectory");
            String log = Files.readString(result.logFile(), StandardCharsets.UTF_8);
            require(log.contains("operation_failure") && log.contains("import"),
                    "A sanitized operation failure must remain diagnostically visible");
            require(!log.contains("secret-value")
                            && !log.contains("private note")
                            && !log.contains("snapshot body"),
                    "Sensitive exception values must never reach persistent logs");
        } finally {
            RingLogLogging.shutdown();
            deleteDirectory(root);
        }
    }

    public static void loggingFilesystemFailureFallsBackWithoutThrowing() throws Exception {
        Path root = Files.createTempDirectory("ringlog-logging-fallback-");
        try {
            AppPaths paths = AppPaths.forDataRoot(root.resolve("appdata"));
            Files.createDirectories(paths.dataRoot());
            Files.writeString(paths.logsDirectory(), "not a directory", StandardCharsets.UTF_8);

            LoggingInitialization result = RingLogLogging.initialize(paths);

            require(!result.persistent(),
                    "A log-only filesystem failure must select stderr fallback");
            require(result.problem() != null && !result.problem().isBlank(),
                    "Fallback must retain a safe diagnostic reason");
        } finally {
            RingLogLogging.shutdown();
            deleteDirectory(root);
        }
    }

    public static void fileLogRotatesAtFiveMegabytesAndKeepsAtMostFiveArchives()
            throws Exception {
        Path root = Files.createTempDirectory("ringlog-logging-rotation-");
        try {
            AppPaths paths = AppPaths.forDataRoot(root.resolve("appdata"));
            LoggingInitialization result = RingLogLogging.initialize(paths);
            require(result.persistent(), "The rotation fixture requires persistent logging");

            String chunk = "x".repeat(256 * 1024);
            var logger = LogManager.getLogger("ringlog.rotation.test");
            for (int index = 0; index < 25; index++) {
                logger.info("rotation-marker-{} {}", index, chunk);
            }
            RingLogLogging.shutdown();

            require(Files.isRegularFile(result.logFile()),
                    "Rotation must retain the active ringlog.log");
            try (var files = Files.list(paths.logsDirectory())) {
                long archives = files.filter(path -> path.getFileName().toString()
                                .matches("ringlog-\\d+\\.log"))
                        .count();
                require(archives >= 1 && archives <= 5,
                        "The 5 MB policy must rotate and retain no more than five archives");
            }
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
        persistentLogUsesAppPathsAndRedactsFailureContent();
        loggingFilesystemFailureFallsBackWithoutThrowing();
        fileLogRotatesAtFiveMegabytesAndKeepsAtMostFiveArchives();
        System.out.println("RingLogLoggingTest: PASS");
    }
}
