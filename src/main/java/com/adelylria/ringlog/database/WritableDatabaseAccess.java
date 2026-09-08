package com.adelylria.ringlog.database;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

import com.j256.ormlite.support.ConnectionSource;

/** Normal desktop access to one managed RingLog database. */
public final class WritableDatabaseAccess implements DatabaseAccess {

    private final String databaseFile;

    public WritableDatabaseAccess(Path databaseFile) {
        this.databaseFile = Objects.requireNonNull(databaseFile, "databaseFile")
                .toAbsolutePath().normalize().toString();
    }

    public WritableDatabaseAccess(String databaseFile) {
        this(Path.of(Objects.requireNonNull(databaseFile, "databaseFile")));
    }

    @Override
    public Connection openConnection() throws SQLException {
        return Database.getConnection(databaseFile);
    }

    @Override
    public ConnectionSource openConnectionSource() throws SQLException {
        return Database.getConnectionSource(databaseFile);
    }

    @Override
    public boolean readOnly() {
        return false;
    }
}
