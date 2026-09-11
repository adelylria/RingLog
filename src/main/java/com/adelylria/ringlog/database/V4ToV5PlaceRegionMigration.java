package com.adelylria.ringlog.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/** Adds the administrative location fields while preserving every existing place. */
public final class V4ToV5PlaceRegionMigration implements SqlTransactionalMigration {

    @Override
    public int fromSchema() {
        return 4;
    }

    @Override
    public int targetSchema() {
        return 5;
    }

    @Override
    public void migrate(Connection connection) throws SQLException {
        Set<String> columns = placeColumns(connection);
        try (Statement statement = connection.createStatement()) {
            if (!columns.contains("autonomous_community")) {
                statement.execute("ALTER TABLE place ADD COLUMN autonomous_community TEXT");
            }
            if (!columns.contains("country")) {
                statement.execute("ALTER TABLE place ADD COLUMN country TEXT");
            }
        }
    }

    @Override
    public void validate(Connection connection) throws SQLException {
        Set<String> columns = placeColumns(connection);
        if (!columns.contains("autonomous_community") || !columns.contains("country")) {
            throw new SQLException("La migración no creó los campos territoriales del lugar.");
        }
    }

    private static Set<String> placeColumns(Connection connection) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(place)")) {
            while (rows.next()) {
                columns.add(rows.getString("name"));
            }
        }
        return columns;
    }
}
