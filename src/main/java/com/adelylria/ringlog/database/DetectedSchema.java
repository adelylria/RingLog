package com.adelylria.ringlog.database;

/** Physical PRAGMA value plus RingLog's logical schema interpretation. */
public record DetectedSchema(int pragmaVersion, int logicalVersion) {
}
