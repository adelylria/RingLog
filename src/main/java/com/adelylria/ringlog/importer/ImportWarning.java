package com.adelylria.ringlog.importer;

public record ImportWarning(
        String severity,
        String source,
        String reference,
        String message
) {

    public boolean requiresReview() {
        return "REVIEW".equalsIgnoreCase(severity);
    }
}
