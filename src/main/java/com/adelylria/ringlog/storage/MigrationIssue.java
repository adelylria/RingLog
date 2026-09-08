package com.adelylria.ringlog.storage;

import java.nio.file.Path;
import java.util.List;

/** A preserved migration problem that can support future user-assisted relocation. */
public record MigrationIssue(
        String table,
        String stableKey,
        String originalReference,
        MediaStatus mediaStatus,
        List<Path> candidates,
        String message
) {
    public MigrationIssue {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
