package com.adelylria.ringlog.storage;

import java.nio.file.Path;

public record DataLocationAssessment(
        DataLocationState state,
        DatabaseCandidate legacyDatabase,
        DatabaseCandidate managedDatabase
) {
    public Path preferredDatabase() {
        return switch (state) {
            case MANAGED_ONLY, BOTH_IDENTICAL -> managedDatabase.path();
            case LEGACY_ONLY -> legacyDatabase.path();
            case NO_DATABASE, BOTH_DIFFERENT -> null;
        };
    }
}
