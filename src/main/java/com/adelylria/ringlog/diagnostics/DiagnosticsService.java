package com.adelylria.ringlog.diagnostics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;

import com.adelylria.ringlog.database.SchemaVersionDetector;
import com.adelylria.ringlog.storage.DataPaths;

/** Builds a privacy-safe runtime snapshot without reading domain rows. */
public final class DiagnosticsService {

    public DiagnosticsSnapshot snapshot(DataPaths appPaths) {
        DataPaths paths = Objects.requireNonNull(appPaths, "appPaths");
        Path database = paths.databasePath().toAbsolutePath().normalize();
        int schema = schemaVersion(database);
        Path configuredLog = RingLogLogging.logFile();
        Path log = configuredLog == null
                ? paths.logsDirectory().resolve("ringlog.log").toAbsolutePath().normalize()
                : configuredLog;
        return new DiagnosticsSnapshot(
                BuildInfo.applicationVersion(),
                BuildInfo.javaVersion(),
                BuildInfo.operatingSystem(),
                paths.dataRoot(),
                database,
                schema,
                log
        );
    }

    private static int schemaVersion(Path database) {
        if (!Files.isRegularFile(database)) {
            return -1;
        }
        try {
            return new SchemaVersionDetector().detect(database).logicalVersion();
        } catch (SQLException failure) {
            return -1;
        }
    }
}
