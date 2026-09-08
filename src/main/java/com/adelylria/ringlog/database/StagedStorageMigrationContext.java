package com.adelylria.ringlog.database;

import java.util.Objects;

import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.MigrationProgressListener;

/** Runtime dependencies for a migration that publishes database and media from staging. */
public record StagedStorageMigrationContext(
        AppPaths appPaths,
        MigrationProgressListener progressListener
) {

    public StagedStorageMigrationContext {
        Objects.requireNonNull(appPaths, "appPaths");
        progressListener = progressListener == null
                ? MigrationProgressListener.NONE : progressListener;
    }
}
