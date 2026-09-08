package com.adelylria.ringlog.importexport;

/** A safe, user-facing explanation of why an interchange source was rejected. */
public final class ImportValidationException extends Exception {

    public ImportValidationException(String message) {
        super(message);
    }

    public ImportValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
