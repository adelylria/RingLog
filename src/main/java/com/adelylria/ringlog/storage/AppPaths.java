package com.adelylria.ringlog.storage;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Stable locations for RingLog data that are independent from the working directory. */
public final class AppPaths implements DataPaths {

    public static final String DATA_DIRECTORY_PROPERTY = "ringlog.data.dir";
    public static final String TEST_MODE_PROPERTY = "ringlog.test.mode";

    private final Path dataRoot;

    private AppPaths(Path dataRoot) {
        this.dataRoot = normalizedRoot(dataRoot);
    }

    public static AppPaths production() {
        return fromEnvironment(new PathEnvironment() {
            @Override
            public String environmentVariable(String name) {
                return System.getenv(name);
            }

            @Override
            public String systemProperty(String name) {
                return System.getProperty(name);
            }
        });
    }

    static AppPaths fromEnvironment(PathEnvironment environment) {
        Objects.requireNonNull(environment, "environment");
        String localAppData = text(environment.environmentVariable("LOCALAPPDATA"));
        Path productionRoot = localAppData == null
                ? null : Path.of(localAppData, "RingLog").toAbsolutePath().normalize();
        String override = text(environment.systemProperty(DATA_DIRECTORY_PROPERTY));
        boolean testMode = Boolean.parseBoolean(textOrEmpty(
                environment.systemProperty(TEST_MODE_PROPERTY)
        ));

        if (testMode && override == null) {
            throw new IllegalStateException(
                    "Los tests de RingLog requieren una raíz temporal explícita."
            );
        }
        if (override != null) {
            Path explicit = normalizedRoot(Path.of(override));
            if (testMode && productionRoot != null && explicit.equals(productionRoot)) {
                throw new IllegalStateException(
                        "Un test no puede utilizar el directorio de datos de producción."
                );
            }
            return new AppPaths(explicit);
        }
        if (productionRoot == null) {
            throw new IllegalStateException(
                    "Windows no ha proporcionado LOCALAPPDATA para guardar RingLog."
            );
        }
        return new AppPaths(productionRoot);
    }

    public static AppPaths forDataRoot(Path dataRoot) {
        return new AppPaths(dataRoot);
    }

    /** Compatibility factory for explicit test/support databases outside the standard layout. */
    public static AppPaths forDatabase(Path database) {
        Path normalized = Objects.requireNonNull(database, "database")
                .toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("La base de datos debe tener un directorio padre.");
        }
        Path root = parent.getFileName() != null
                && "data".equalsIgnoreCase(parent.getFileName().toString())
                && parent.getParent() != null
                ? parent.getParent() : parent;
        return new AppPaths(root);
    }

    public Path dataRoot() {
        return dataRoot;
    }

    public Path databasePath() {
        return dataRoot.resolve("data").resolve("ringlog.db");
    }

    public Path photosDirectory() {
        return dataRoot.resolve("photos");
    }

    public Path eventPhotosDirectory() {
        return photosDirectory().resolve("events");
    }

    public Path nativePhotosDirectory() {
        return photosDirectory().resolve("native");
    }

    public Path unassignedPhotosDirectory() {
        return dataRoot.resolve("unassigned-photos");
    }

    public Path backupsDirectory() {
        return dataRoot.resolve("backups");
    }

    public Path logsDirectory() {
        return dataRoot.resolve("logs");
    }

    public Path migrationDirectory() {
        return dataRoot.resolve("migration");
    }

    private static Path normalizedRoot(Path root) {
        Objects.requireNonNull(root, "dataRoot");
        Path normalized = root.toAbsolutePath().normalize();
        if (normalized.getParent() == null) {
            throw new IllegalArgumentException(
                    "La raíz de datos de RingLog no puede ser la raíz del sistema."
            );
        }
        return normalized;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}
