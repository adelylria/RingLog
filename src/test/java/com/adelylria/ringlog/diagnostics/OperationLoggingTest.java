package com.adelylria.ringlog.diagnostics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.ui.importexport.ImportExportController;

/** Checks lifecycle visibility without leaking selected source/destination names. */
public final class OperationLoggingTest {

    private OperationLoggingTest() {
    }

    public static void importAndExportLifecycleUsesFixedSafeLabels() throws Exception {
        Path root = Files.createTempDirectory("ringlog-operation-log-");
        try {
            AppPaths paths = AppPaths.forDataRoot(root.resolve("appdata"));
            Database.initialize(paths.databasePath().toString());
            LoggingInitialization logging = RingLogLogging.initialize(paths);
            ImportExportController controller = ImportExportController.application(paths);

            String privateSourceName = "persona-anilla-secreta.xlsx";
            Path invalid = root.resolve(privateSourceName);
            Files.writeString(invalid, "invalid", StandardCharsets.UTF_8);
            try {
                controller.analyze(invalid);
            } catch (Exception expected) {
                // The lifecycle failure is what this fixture needs.
            }

            String privateDestinationName = "copia-personal-no-registrar";
            controller.exportTo(root.resolve(privateDestinationName));
            RingLogLogging.shutdown();

            String log = Files.readString(logging.logFile(), StandardCharsets.UTF_8);
            require(log.contains("operation_start name=import_analysis")
                            && log.contains("operation_failure name=import_analysis"),
                    "Import analysis must log start and sanitized failure lifecycle events");
            require(log.contains("operation_start name=native_export")
                            && log.contains("operation_complete name=native_export"),
                    "Native export must log start and completion lifecycle events");
            require(!log.contains(privateSourceName)
                            && !log.contains(privateDestinationName),
                    "Lifecycle logging must never persist selected filenames");
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
        importAndExportLifecycleUsesFixedSafeLabels();
        System.out.println("OperationLoggingTest: PASS");
    }
}
