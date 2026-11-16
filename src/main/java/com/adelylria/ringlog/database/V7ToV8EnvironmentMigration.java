package com.adelylria.ringlog.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.adelylria.ringlog.model.EnvironmentDefaults;
import com.adelylria.ringlog.model.view.EnvironmentOption;

/** Adds configurable choices without rewriting any historical environmental text. */
public final class V7ToV8EnvironmentMigration implements SqlTransactionalMigration {
    @Override public int fromSchema() { return 7; }
    @Override public int targetSchema() { return 8; }

    @Override
    public void migrate(Connection connection) throws SQLException {
        // Only creating the catalog seeds it: rerunning must not restore removed options.
        try (Statement statement = connection.createStatement();
             ResultSet tables = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'environment_option'")) {
            if (tables.next()) return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE environment_option (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        stable_key TEXT NOT NULL CHECK (length(trim(stable_key)) > 0),
                        category TEXT NOT NULL CHECK (category IN ('CLOUDS', 'RAIN', 'THERMAL_SENSATION', 'WIND')),
                        name TEXT NOT NULL COLLATE NOCASE CHECK (length(trim(name)) > 0),
                        active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
                        sort_order INTEGER NOT NULL DEFAULT 0 CHECK (typeof(sort_order) = 'integer' AND sort_order >= 0),
                        UNIQUE (category, name)
                    );
                    """);
            statement.execute("CREATE UNIQUE INDEX idx_environment_option_stable_key ON environment_option(stable_key)");
            statement.execute("CREATE INDEX idx_environment_option_category_order ON environment_option(category, sort_order, id)");
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO environment_option(stable_key, category, name, sort_order) VALUES (?, ?, ?, ?)
                """)) {
            for (EnvironmentOption option : EnvironmentDefaults.options()) {
                insert.setString(1, option.stableKey());
                insert.setString(2, option.category().code());
                insert.setString(3, option.name());
                insert.setInt(4, option.sortOrder());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    @Override
    public void validate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet invalid = statement.executeQuery("""
                     SELECT id FROM environment_option
                     WHERE category NOT IN ('CLOUDS', 'RAIN', 'THERMAL_SENSATION', 'WIND')
                        OR trim(stable_key) = '' OR trim(name) = ''
                        OR active NOT IN (0, 1) OR sort_order < 0 OR typeof(sort_order) <> 'integer'
                     LIMIT 1
                     """)) {
            if (invalid.next()) throw new SQLException("El catálogo de Entorno contiene opciones inválidas.");
        }
    }
}
