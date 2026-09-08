package com.adelylria.ringlog.storage;

import java.nio.file.Path;
import java.util.List;

public record MigrationResult(
        MigrationStatus status,
        Path sourceDatabase,
        Path destinationDatabase,
        Path operationDirectory,
        List<MigrationIssue> issues,
        int migratedMediaCount
) {
    public MigrationResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
