package com.adelylria.ringlog.importexport;

/** Signals that a validated import could not be committed safely. */
public class ImportExecutionException extends Exception {

    public ImportExecutionException(String message) {
        super(message);
    }

    public ImportExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
