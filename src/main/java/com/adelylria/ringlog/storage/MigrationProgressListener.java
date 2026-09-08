package com.adelylria.ringlog.storage;

@FunctionalInterface
public interface MigrationProgressListener {

    MigrationProgressListener NONE = progress -> { };

    void onProgress(MigrationProgress progress);
}
