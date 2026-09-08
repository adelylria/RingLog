package com.adelylria.ringlog.storage;

public enum MigrationStage {
    STAGING_CREATED,
    SQLITE_SNAPSHOT_CREATED,
    MEDIA_STAGED,
    SCHEMA_V4_MARKED,
    MEDIA_PUBLISHED,
    DATABASE_PUBLISHED
}
