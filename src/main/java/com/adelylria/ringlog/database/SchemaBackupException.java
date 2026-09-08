package com.adelylria.ringlog.database;

/** Raised when a pre-schema backup cannot be safely created and validated. */
public final class SchemaBackupException extends Exception {

    public SchemaBackupException(String message, Throwable cause) {
        super(message, cause);
    }
}
