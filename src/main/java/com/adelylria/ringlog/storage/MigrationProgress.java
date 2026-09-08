package com.adelylria.ringlog.storage;

import java.nio.file.Path;

public record MigrationProgress(
        MigrationStage stage,
        Path operationDirectory,
        Path stagingDatabase
) {
}
