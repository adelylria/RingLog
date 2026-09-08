package com.adelylria.ringlog.update;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.adelylria.ringlog.preferences.ApplicationPreferences;

public final class UpdatePreferencesTest {

    private UpdatePreferencesTest() {
    }

    public static void automaticChecksAreClaimedAtMostOncePerDay() throws Exception {
        Path file = Path.of(System.getProperty("ringlog.data.dir"), "preferences.properties");
        ApplicationPreferences store = new ApplicationPreferences(file);
        Instant now = Instant.parse("2026-09-01T10:00:00Z");
        UpdatePreferences preferences = new UpdatePreferences(
                store, Clock.fixed(now, ZoneOffset.UTC), Duration.ofHours(24)
        );
        require(preferences.claimAutomaticCheck(), "The first check must be allowed");
        require(!preferences.claimAutomaticCheck(), "A second immediate check must be throttled");

        UpdatePreferences tomorrow = new UpdatePreferences(
                store,
                Clock.fixed(now.plus(Duration.ofHours(24)), ZoneOffset.UTC),
                Duration.ofHours(24)
        );
        require(tomorrow.claimAutomaticCheck(), "A check after 24 hours must be allowed");
        require(Files.readString(file).contains("updates.lastUpdateCheck"),
                "The technical update timestamp must be persisted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        automaticChecksAreClaimedAtMostOncePerDay();
        System.out.println("UpdatePreferencesTest: PASS");
    }
}
