package com.adelylria.ringlog.database;

import java.sql.Connection;
import java.sql.SQLException;

/** A migration whose complete mutation can run inside one SQLite transaction. */
public interface SqlTransactionalMigration extends SchemaMigration {

    @Override
    default SchemaMigrationExecutionType executionType() {
        return SchemaMigrationExecutionType.SQL_TRANSACTIONAL;
    }

    void migrate(Connection connection) throws SQLException;

    default void validate(Connection connection) throws SQLException {
        // Optional step-specific validation; global integrity still runs before commit.
    }
}
