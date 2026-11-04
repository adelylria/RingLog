package com.adelylria.ringlog.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Adds reusable capture methods without assigning a method to historical events. */
public final class V5ToV6CaptureMethodMigration implements SqlTransactionalMigration {

    @Override
    public int fromSchema() { return 5; }

    @Override
    public int targetSchema() { return 6; }

    @Override
    public void migrate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS capture_method (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        stable_key TEXT NOT NULL CHECK (length(trim(stable_key)) > 0),
                        name TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK (length(trim(name)) > 0),
                        active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1))
                    )
                    """);
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_capture_method_stable_key ON capture_method(stable_key)");
            statement.execute("""
                    INSERT OR IGNORE INTO capture_method(stable_key, name) VALUES
                        ('39d6ec44-2b19-4b49-a69c-d2af0aa31f8d', 'FILATS'),
                        ('9145f480-ef6b-4392-a33b-48d4e9bf5b11', 'RED JAPONESA')
                    """);
            boolean hasMethod = false;
            try (ResultSet columns = statement.executeQuery("PRAGMA table_info(bird_event)")) {
                while (columns.next()) {
                    if ("capture_method_id".equals(columns.getString("name"))) hasMethod = true;
                }
            }
            if (!hasMethod) {
                statement.execute("""
                        ALTER TABLE bird_event ADD COLUMN capture_method_id INTEGER
                        REFERENCES capture_method(id) ON UPDATE CASCADE ON DELETE RESTRICT
                        """);
            }
            statement.execute("CREATE INDEX IF NOT EXISTS idx_event_capture_method ON bird_event(capture_method_id)");
        }
    }

    @Override
    public void validate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT COUNT(*) FROM capture_method
                     WHERE stable_key IN ('39d6ec44-2b19-4b49-a69c-d2af0aa31f8d',
                                          '9145f480-ef6b-4392-a33b-48d4e9bf5b11')
                     """)) {
            if (!result.next() || result.getLong(1) != 2) {
                throw new SQLException("La migración no creó los métodos de captura iniciales.");
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT capture_method_id FROM bird_event LIMIT 1")) {
            if (result.next() && result.getObject(1) != null) {
                throw new SQLException("La migración asignó un método a un registro histórico.");
            }
        }
    }
}
