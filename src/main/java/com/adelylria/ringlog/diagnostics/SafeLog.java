package com.adelylria.ringlog.diagnostics;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Allow-listed lifecycle logging that never accepts whole domain entities. */
public final class SafeLog {

    private static final Logger LOGGER = LogManager.getLogger("RingLog");

    private SafeLog() {
    }

    public static void startup() {
        LOGGER.info(
                "startup appVersion={} java={} os={}",
                BuildInfo.applicationVersion(),
                BuildInfo.javaVersion(),
                BuildInfo.operatingSystem()
        );
    }

    public static void schemaUpgrade(int fromSchema, int targetSchema, int steps) {
        LOGGER.info(
                "schema_upgrade from={} target={} steps={}",
                fromSchema,
                targetSchema,
                steps
        );
    }

    public static void operationStarted(String operation) {
        LOGGER.info("operation_start name={}", LogSanitizer.label(operation));
    }

    public static void operationCompleted(String operation, long durationMillis, long count) {
        LOGGER.info(
                "operation_complete name={} durationMs={} count={}",
                LogSanitizer.label(operation),
                Math.max(0, durationMillis),
                Math.max(0, count)
        );
    }

    public static void failure(String operation, Throwable failure) {
        String safeOperation = LogSanitizer.label(operation);
        String reason = failure == null ? "UnknownFailure" : failure.getClass().getSimpleName();
        if (!RingLogLogging.isPersistent()) {
            System.err.println("RingLog operation_failure name=" + safeOperation
                    + " reason=" + reason);
            return;
        }
        LOGGER.error(
                "operation_failure name={} reason={}",
                safeOperation,
                reason
        );
    }

    public static void warning(String operation, Throwable failure) {
        String safeOperation = LogSanitizer.label(operation);
        String reason = failure == null ? "UnknownFailure" : failure.getClass().getSimpleName();
        if (!RingLogLogging.isPersistent()) {
            System.err.println("RingLog operation_warning name=" + safeOperation
                    + " reason=" + reason);
            return;
        }
        LOGGER.warn("operation_warning name={} reason={}", safeOperation, reason);
    }
}
