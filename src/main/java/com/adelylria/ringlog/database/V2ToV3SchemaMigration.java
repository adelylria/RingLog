package com.adelylria.ringlog.database;

import java.sql.Connection;
import java.sql.SQLException;

/** SQL-only migration that introduces native import/export traceability. */
public final class V2ToV3SchemaMigration implements SqlTransactionalMigration {

    @Override
    public int fromSchema() {
        return 2;
    }

    @Override
    public int targetSchema() {
        return 3;
    }

    @Override
    public void migrate(Connection connection) throws SQLException {
        SchemaMigrator.applyV2ToV3(connection);
    }
}
