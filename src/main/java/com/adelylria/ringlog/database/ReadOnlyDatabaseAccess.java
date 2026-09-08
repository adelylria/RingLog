package com.adelylria.ringlog.database;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

import com.j256.ormlite.support.ConnectionSource;

/** SQLite access that cannot create or mutate the selected database. */
public final class ReadOnlyDatabaseAccess implements DatabaseAccess {

    private final String databaseFile;

    public ReadOnlyDatabaseAccess(Path databaseFile) {
        this.databaseFile = Objects.requireNonNull(databaseFile, "databaseFile")
                .toAbsolutePath().normalize().toString();
    }

    @Override
    public Connection openConnection() throws SQLException {
        return Database.getReadOnlyConnection(databaseFile);
    }

    @Override
    public ConnectionSource openConnectionSource() throws SQLException {
        return Database.getReadOnlyConnectionSource(databaseFile);
    }

    @Override
    public boolean readOnly() {
        return true;
    }
}
