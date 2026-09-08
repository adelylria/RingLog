package com.adelylria.ringlog.storage;

import java.nio.file.Path;

public record StartupStorageResult(
        StartupStorageStatus status,
        Path database,
        Path migrationSource,
        DataLocationAssessment assessment
) {
}
