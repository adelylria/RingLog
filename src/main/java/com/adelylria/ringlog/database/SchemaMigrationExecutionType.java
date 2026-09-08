package com.adelylria.ringlog.database;

/** Defines the safety model required to execute a schema migration step. */
public enum SchemaMigrationExecutionType {
    SQL_TRANSACTIONAL,
    STAGED_STORAGE
}
