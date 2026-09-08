package com.adelylria.ringlog.database;

import java.sql.Connection;
import java.sql.SQLException;

import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.support.ConnectionSource;

/** One fixed access policy for a database. */
public interface DatabaseAccess {

    Connection openConnection() throws SQLException;

    ConnectionSource openConnectionSource() throws SQLException;

    boolean readOnly();

    default <T> T withOrm(OrmOperation<T> operation) throws SQLException {
        ConnectionSource source = openConnectionSource();
        try {
            return operation.execute(source);
        } finally {
            DaoManager.unregisterDaos(source);
            source.closeQuietly();
        }
    }

    @FunctionalInterface
    interface OrmOperation<T> {
        T execute(ConnectionSource source) throws SQLException;
    }
}
