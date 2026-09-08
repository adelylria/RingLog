package com.adelylria.ringlog.update;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

import com.adelylria.ringlog.preferences.ApplicationPreferences;

public final class UpdatePreferences {

    public static final String LAST_UPDATE_CHECK = "updates.lastUpdateCheck";

    private final ApplicationPreferences preferences;
    private final Clock clock;
    private final Duration interval;

    public UpdatePreferences(
            ApplicationPreferences preferences,
            Clock clock,
            Duration interval
    ) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("Update interval must be positive");
        }
    }

    public static UpdatePreferences production() {
        return new UpdatePreferences(
                ApplicationPreferences.production(), Clock.systemUTC(), Duration.ofHours(24)
        );
    }

    public synchronized boolean claimAutomaticCheck() throws IOException {
        Instant now = clock.instant();
        String previous = preferences.get(LAST_UPDATE_CHECK);
        if (previous != null) {
            try {
                Instant last = Instant.parse(previous);
                if (now.isBefore(last.plus(interval))) {
                    return false;
                }
            } catch (DateTimeParseException ignored) {
                // Invalid technical state is repaired below.
            }
        }
        preferences.put(LAST_UPDATE_CHECK, now.toString());
        return true;
    }
}
