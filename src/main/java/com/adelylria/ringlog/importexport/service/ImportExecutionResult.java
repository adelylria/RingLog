package com.adelylria.ringlog.importexport.service;

/** Outcome of one atomic import attempt. */
public record ImportExecutionResult(Status status, long importBatchId) {

    public enum Status {
        APPLIED,
        ALREADY_IMPORTED
    }
}
