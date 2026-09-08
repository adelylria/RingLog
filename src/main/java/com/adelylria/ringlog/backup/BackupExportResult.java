package com.adelylria.ringlog.backup;

import java.nio.file.Path;

public record BackupExportResult(
        Path file,
        int speciesCount,
        int birdsCount,
        int placesCount,
        int eventsCount,
        int photosCount
) {
    public BackupExportResult {
        file = file.toAbsolutePath().normalize();
    }
}
