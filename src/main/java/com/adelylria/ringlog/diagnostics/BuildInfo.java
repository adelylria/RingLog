package com.adelylria.ringlog.diagnostics;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Technical build/runtime values safe to show in diagnostics and logs. */
public final class BuildInfo {

    private static final String BUILD_METADATA = "/ringlog-build.properties";
    private static final String DEVELOPMENT_VERSION = "development";

    private BuildInfo() {
    }

    public static String applicationVersion() {
        String implementationVersion = nonBlank(
                BuildInfo.class.getPackage().getImplementationVersion()
        );
        if (implementationVersion != null) {
            return implementationVersion;
        }

        String resourceVersion = buildResourceVersion();
        return resourceVersion == null ? DEVELOPMENT_VERSION : resourceVersion;
    }

    public static String javaVersion() {
        return System.getProperty("java.version", "unknown");
    }

    public static String operatingSystem() {
        return System.getProperty("os.name", "unknown") + " "
                + System.getProperty("os.version", "unknown");
    }

    private static String buildResourceVersion() {
        Properties properties = new Properties();
        try (InputStream input = BuildInfo.class.getResourceAsStream(BUILD_METADATA)) {
            if (input == null) {
                return null;
            }
            properties.load(input);
            return nonBlank(properties.getProperty("version"));
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
