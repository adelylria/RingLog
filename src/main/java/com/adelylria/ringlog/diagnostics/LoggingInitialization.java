package com.adelylria.ringlog.diagnostics;

import java.nio.file.Path;

/** Non-throwing result of persistent logging initialization. */
public record LoggingInitialization(
        boolean persistent,
        Path logFile,
        String problem
) {
}
