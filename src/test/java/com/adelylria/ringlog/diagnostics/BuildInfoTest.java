package com.adelylria.ringlog.diagnostics;

import java.util.regex.Pattern;

/** Contract for the version visible from Maven metadata and the packaged JAR. */
public final class BuildInfoTest {

    private static final Pattern SUPPORTED_VERSION = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-SNAPSHOT)?$"
    );

    private BuildInfoTest() {
    }

    public static void appVersionComesFromBuildMetadata() {
        String version = BuildInfo.applicationVersion();
        require(!"development".equals(version),
                "A Maven build must expose its filtered build version");
        require(SUPPORTED_VERSION.matcher(version).matches(),
                "The application version must follow the approved SemVer subset: " + version);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        appVersionComesFromBuildMetadata();
        System.out.println("BuildInfoTest: PASS");
    }
}
