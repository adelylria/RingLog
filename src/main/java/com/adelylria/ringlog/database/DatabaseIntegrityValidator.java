package com.adelylria.ringlog.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Performs schema-independent SQLite integrity checks for staged databases. */
public final class DatabaseIntegrityValidator {

    public void requireValid(Connection connection, int expectedLogicalSchema)
            throws SQLException {
        int pragmaVersion = SchemaVersionDetector.userVersion(connection);
        boolean versionMatches = expectedLogicalSchema == 2
                ? pragmaVersion == 0 && SchemaMigrator.hasExactV2CoreTables(connection)
                : pragmaVersion == expectedLogicalSchema;
        if (!versionMatches) {
            throw new SQLException(
                    "La copia SQLite no conserva el schema esperado v" + expectedLogicalSchema + '.'
            );
        }

        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new SQLException("SQLite quick_check ha detectado daños en la copia.");
            }
        }

        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (result.next()) {
                throw new SQLException("La copia SQLite contiene referencias externas inválidas.");
            }
        }
    }
}
