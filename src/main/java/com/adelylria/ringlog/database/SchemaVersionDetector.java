package com.adelylria.ringlog.database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Detects supported schema generations without mutating the database. */
public final class SchemaVersionDetector {

    public DetectedSchema detect(Path database) throws SQLException {
        Path source = requireDatabase(database);
        try (Connection connection = Database.getReadOnlyConnection(source.toString())) {
            int pragmaVersion = userVersion(connection);
            if (pragmaVersion == 0) {
                if (SchemaMigrator.hasExactV2CoreTables(connection)) {
                    return new DetectedSchema(0, 2);
                }
                throw new SQLException(
                        "La base de datos no declara una versión y no coincide con RingLog v2."
                );
            }
            return new DetectedSchema(pragmaVersion, pragmaVersion);
        }
    }

    static int userVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            if (!result.next()) {
                throw new SQLException("No se pudo leer PRAGMA user_version.");
            }
            return result.getInt(1);
        }
    }

    private static Path requireDatabase(Path database) throws SQLException {
        if (database == null) {
            throw new SQLException("No se ha indicado la base de datos de RingLog.");
        }
        Path normalized = database.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new SQLException("No existe la base de datos de RingLog.");
        }
        return normalized;
    }
}
