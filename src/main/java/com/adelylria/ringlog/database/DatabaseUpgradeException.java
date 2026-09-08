package com.adelylria.ringlog.database;

/** Safe application-level failure raised by the schema upgrade orchestrator. */
public class DatabaseUpgradeException extends Exception {

    public DatabaseUpgradeException(String message) {
        super(message);
    }

    public DatabaseUpgradeException(String message, Throwable cause) {
        super(message, cause);
    }
}
