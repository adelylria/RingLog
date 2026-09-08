package com.adelylria.ringlog.update;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** RingLog's intentionally small release version contract: MAJOR.MINOR.PATCH. */
public record SemanticVersion(int major, int minor, int patch)
        implements Comparable<SemanticVersion> {

    private static final Pattern PATTERN = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$"
    );

    public SemanticVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version components cannot be negative");
        }
    }

    public static Optional<SemanticVersion> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher matcher = PATTERN.matcher(value.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SemanticVersion(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
            ));
        } catch (NumberFormatException overflow) {
            return Optional.empty();
        }
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int result = Integer.compare(major, other.major);
        if (result == 0) {
            result = Integer.compare(minor, other.minor);
        }
        if (result == 0) {
            result = Integer.compare(patch, other.patch);
        }
        return result;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
