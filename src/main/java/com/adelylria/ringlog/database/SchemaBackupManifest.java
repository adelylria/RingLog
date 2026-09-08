package com.adelylria.ringlog.database;

/** Privacy-minimal metadata written next to a validated schema backup. */
record SchemaBackupManifest(
        int fromSchema,
        int targetSchema,
        String appVersion,
        String createdAt,
        String sha256,
        String databaseFile
) {

    String toJson() {
        return """
                {
                  "fromSchema": %d,
                  "targetSchema": %d,
                  "appVersion": "%s",
                  "createdAt": "%s",
                  "sha256": "%s",
                  "databaseFile": "%s"
                }
                """.formatted(
                fromSchema,
                targetSchema,
                escape(appVersion),
                escape(createdAt),
                escape(sha256),
                escape(databaseFile)
        );
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
