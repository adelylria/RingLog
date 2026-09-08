package com.adelylria.ringlog.storage;

import java.nio.file.Path;
import java.util.Map;

public final class AppPathsTest {

    private AppPathsTest() {
    }

    public static void appPathsProductionUsesLocalAppData() {
        Path localAppData = Path.of("C:\\Users\\ringlog-test\\AppData\\Local");
        AppPaths paths = AppPaths.fromEnvironment(environment(
                Map.of("LOCALAPPDATA", localAppData.toString()), Map.of()
        ));

        Path expectedRoot = localAppData.resolve("RingLog").toAbsolutePath().normalize();
        require(paths.dataRoot().equals(expectedRoot),
                "Production data must live below LOCALAPPDATA/RingLog");
        require(paths.databasePath().equals(expectedRoot.resolve("data/ringlog.db")),
                "The database must live in the managed data directory");
        require(paths.eventPhotosDirectory().equals(expectedRoot.resolve("photos/events")),
                "Event photos must have their own managed directory");
        require(paths.nativePhotosDirectory().equals(expectedRoot.resolve("photos/native")),
                "Restored photos must have their own managed directory");
        require(paths.unassignedPhotosDirectory().equals(
                        expectedRoot.resolve("unassigned-photos")),
                "Unassigned photos must not be mixed with event photos");
        require(paths.backupsDirectory().equals(expectedRoot.resolve("backups")),
                "Backups directory is not derived from the data root");
        require(paths.logsDirectory().equals(expectedRoot.resolve("logs")),
                "Logs directory is not derived from the data root");
        require(paths.migrationDirectory().equals(expectedRoot.resolve("migration")),
                "Migration staging is not derived from the data root");
    }

    public static void appPathsOverrideUsesExplicitDirectory() {
        Path override = Path.of("C:\\RingLog-Support\\profile-a");
        AppPaths paths = AppPaths.fromEnvironment(environment(
                Map.of("LOCALAPPDATA", "C:\\Ignored"),
                Map.of(AppPaths.DATA_DIRECTORY_PROPERTY, override.toString())
        ));

        require(paths.dataRoot().equals(override.toAbsolutePath().normalize()),
                "The support override must take precedence over LOCALAPPDATA");
    }

    public static void testsCannotUseProductionDataRoot() {
        Path localAppData = Path.of("C:\\Users\\ringlog-test\\AppData\\Local");
        Path production = localAppData.resolve("RingLog");
        try {
            AppPaths.fromEnvironment(environment(
                    Map.of("LOCALAPPDATA", localAppData.toString()),
                    Map.of(
                            AppPaths.DATA_DIRECTORY_PROPERTY, production.toString(),
                            AppPaths.TEST_MODE_PROPERTY, "true"
                    )
            ));
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("A test process must fail before using production AppData");
    }

    private static PathEnvironment environment(
            Map<String, String> variables,
            Map<String, String> properties
    ) {
        return new PathEnvironment() {
            @Override
            public String environmentVariable(String name) {
                return variables.get(name);
            }

            @Override
            public String systemProperty(String name) {
                return properties.get(name);
            }
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        appPathsProductionUsesLocalAppData();
        appPathsOverrideUsesExplicitDirectory();
        testsCannotUseProductionDataRoot();
        System.out.println("AppPathsTest: PASS");
    }
}
