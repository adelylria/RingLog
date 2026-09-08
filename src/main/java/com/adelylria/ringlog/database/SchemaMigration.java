package com.adelylria.ringlog.database;

/** Metadata contract shared by every ordered database migration step. */
public interface SchemaMigration {

    int fromSchema();

    int targetSchema();

    SchemaMigrationExecutionType executionType();
}
