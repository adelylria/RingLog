package com.adelylria.ringlog.update;

public final class SemanticVersionTest {

    private SemanticVersionTest() {
    }

    public static void numericComponentsAreCompared() {
        SemanticVersion older = SemanticVersion.parse("1.0.9").orElseThrow();
        SemanticVersion newer = SemanticVersion.parse("1.0.10").orElseThrow();
        require(newer.compareTo(older) > 0, "1.0.10 must be newer than 1.0.9");
        require(older.compareTo(newer) < 0, "Semantic version ordering must be symmetric");
    }

    public static void unsupportedVersionsAreRejected() {
        for (String value : new String[]{"1.0", "01.0.0", "1.0.0-rc1", "1.0.0-SNAPSHOT", "x"}) {
            require(SemanticVersion.parse(value).isEmpty(), "Invalid update version accepted: " + value);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        numericComponentsAreCompared();
        unsupportedVersionsAreRejected();
        System.out.println("SemanticVersionTest: PASS");
    }
}
