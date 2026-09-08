package com.adelylria.ringlog.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the private, real-world legacy integration fixture without embedding
 * a developer-specific path in the public source tree.
 */
public final class LegacyV52TestFixture {

    private static final String ENVIRONMENT_VARIABLE = "RINGLOG_LEGACY_V52_FIXTURE";

    private LegacyV52TestFixture() {
    }

    public static Path path() {
        String configured = System.getenv(ENVIRONMENT_VARIABLE);
        Path candidate;
        if (configured != null && !configured.isBlank()) {
            candidate = Path.of(configured);
        } else {
            candidate = Path.of(
                    "..",
                    "RingLogLegacyMigrator",
                    "output",
                    "ringlog-import.xlsx"
            );
        }
        return candidate.toAbsolutePath().normalize();
    }

    public static boolean skipIfUnavailable(String testName) {
        Path fixture = path();
        if (Files.isRegularFile(fixture)) {
            return false;
        }
        System.out.println(
                testName + ": SKIP (set " + ENVIRONMENT_VARIABLE
                        + " to run the private legacy v5.2 integration fixture)"
        );
        return true;
    }
}
