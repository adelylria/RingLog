package com.adelylria.ringlog.diagnostics;

import java.nio.file.Path;

/** Technical diagnostics safe for the Settings screen. */
public record DiagnosticsSnapshot(
        String applicationVersion,
        String javaVersion,
        String operatingSystem,
        Path dataRoot,
        Path database,
        int schemaVersion,
        Path logFile
) {
}
