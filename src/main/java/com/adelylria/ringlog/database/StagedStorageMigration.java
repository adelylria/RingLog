package com.adelylria.ringlog.database;

import java.io.IOException;
import java.sql.SQLException;

import com.adelylria.ringlog.storage.MigrationResult;

/** A migration that must validate database and external files in staging. */
public interface StagedStorageMigration extends SchemaMigration {

    @Override
    default SchemaMigrationExecutionType executionType() {
        return SchemaMigrationExecutionType.STAGED_STORAGE;
    }

    MigrationResult migrate(StagedStorageMigrationContext context)
            throws SQLException, IOException;
}
