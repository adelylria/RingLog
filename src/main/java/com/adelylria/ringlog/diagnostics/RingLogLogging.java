package com.adelylria.ringlog.diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;

import com.adelylria.ringlog.storage.DataPaths;

/** Initializes rotating diagnostics while keeping log-only failures non-fatal. */
public final class RingLogLogging {

    public static final String LOGS_DIRECTORY_PROPERTY = "ringlog.logs.dir";

    private static volatile Path logFile;
    private static volatile boolean persistent;

    private RingLogLogging() {
    }

    public static synchronized LoggingInitialization initialize(DataPaths appPaths) {
        Objects.requireNonNull(appPaths, "appPaths");
        Path logsDirectory = appPaths.logsDirectory().toAbsolutePath().normalize();
        Path requestedLog = logsDirectory.resolve("ringlog.log");
        try {
            Files.createDirectories(logsDirectory);
            if (!Files.isDirectory(logsDirectory)) {
                throw new IOException("La ruta de logs no es un directorio.");
            }
            System.setProperty(LOGS_DIRECTORY_PROPERTY, logsDirectory.toString());
            Configurator.reconfigure();
            logFile = requestedLog;
            persistent = true;
            SafeLog.startup();
            if (!Files.isRegularFile(requestedLog)) {
                throw new IOException("Log4j no ha podido crear ringlog.log.");
            }
            return new LoggingInitialization(true, requestedLog, null);
        } catch (IOException | RuntimeException failure) {
            logFile = null;
            persistent = false;
            String safeProblem = LogSanitizer.failure(failure);
            System.err.println("RingLog no pudo iniciar el log persistente; "
                    + "se usará stderr. " + safeProblem);
            return new LoggingInitialization(false, requestedLog, safeProblem);
        }
    }

    public static Path logFile() {
        return logFile;
    }

    static boolean isPersistent() {
        return persistent;
    }

    public static synchronized void shutdown() {
        LogManager.shutdown();
        logFile = null;
        persistent = false;
        System.clearProperty(LOGS_DIRECTORY_PROPERTY);
    }
}
