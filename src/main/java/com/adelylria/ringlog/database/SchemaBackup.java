package com.adelylria.ringlog.database;

import java.nio.file.Path;

/** Published pre-upgrade database backup and its technical manifest. */
public record SchemaBackup(
        Path database,
        Path manifest,
        int fromSchema,
        int targetSchema,
        String appVersion,
        String createdAt,
        String sha256,
        long size
) {
}
