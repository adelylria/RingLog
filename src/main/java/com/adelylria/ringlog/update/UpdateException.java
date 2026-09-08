package com.adelylria.ringlog.update;

/** Safe technical failure raised while checking or preparing an application update. */
public final class UpdateException extends Exception {

    public UpdateException(String message) {
        super(message);
    }

    public UpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}
