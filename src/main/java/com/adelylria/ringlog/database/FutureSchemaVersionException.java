package com.adelylria.ringlog.database;

/** Indicates that this RingLog build is older than the database it was asked to open. */
public final class FutureSchemaVersionException extends DatabaseUpgradeException {

    private final int detectedSchema;
    private final int supportedSchema;

    public FutureSchemaVersionException(int detectedSchema, int supportedSchema) {
        super("La base de datos usa schema v" + detectedSchema
                + ", pero esta versión de RingLog admite hasta v" + supportedSchema + '.');
        this.detectedSchema = detectedSchema;
        this.supportedSchema = supportedSchema;
    }

    public int detectedSchema() {
        return detectedSchema;
    }

    public int supportedSchema() {
        return supportedSchema;
    }
}
