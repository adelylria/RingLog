package com.adelylria.ringlog.storage;

import java.nio.file.Path;
import java.time.Instant;

/** Technical-only proof of a completed location migration. */
public record MigrationReceipt(
        int receiptVersion,
        String completedAt,
        Path sourceDatabase,
        Path destinationDatabase,
        String sourceFingerprint,
        String destinationFingerprint,
        int migratedMediaCount,
        String result
) {
    public static MigrationReceipt completed(
            Path source,
            Path destination,
            String sourceFingerprint,
            String destinationFingerprint,
            int migratedMediaCount
    ) {
        return new MigrationReceipt(
                1,
                Instant.now().toString(),
                source.toAbsolutePath().normalize(),
                destination.toAbsolutePath().normalize(),
                sourceFingerprint,
                destinationFingerprint,
                migratedMediaCount,
                "COMPLETED"
        );
    }
}
