package com.adelylria.ringlog.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Adds optional ambient temperature independently of historical thermal sensation. */
public final class V6ToV7TemperatureMigration implements SqlTransactionalMigration {

    @Override
    public int fromSchema() { return 6; }

    @Override
    public int targetSchema() { return 7; }

    @Override
    public void migrate(Connection connection) throws SQLException {
        boolean present = false;
        try (Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("PRAGMA table_info(bird_event)")) {
            while (columns.next()) {
                if ("temperature_celsius".equals(columns.getString("name"))) present = true;
            }
        }
        if (!present) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        ALTER TABLE bird_event ADD COLUMN temperature_celsius INTEGER
                        CHECK (temperature_celsius IS NULL OR
                            (typeof(temperature_celsius) = 'integer'
                             AND temperature_celsius BETWEEN -99 AND 99))
                        """);
            }
        }
    }

    @Override
    public void validate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet invalid = statement.executeQuery("""
                     SELECT id FROM bird_event
                     WHERE temperature_celsius IS NOT NULL
                       AND (typeof(temperature_celsius) <> 'integer'
                            OR temperature_celsius NOT BETWEEN -99 AND 99)
                     LIMIT 1
                     """)) {
            if (invalid.next()) {
                throw new SQLException("La temperatura ambiental debe ser un entero entre -99 y 99.");
            }
        }
    }
}
