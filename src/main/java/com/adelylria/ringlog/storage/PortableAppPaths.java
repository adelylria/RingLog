package com.adelylria.ringlog.storage;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Self-contained paths rooted beside the JAR of a portable RingLog copy. */
public final class PortableAppPaths implements DataPaths {

    public static final String INFO_FILE = "portable-info.json";

    private final Path root;

    public PortableAppPaths(Path root) {
        this.root = normalizeRoot(root);
    }

    public static PortableAppPaths fromRunningJar(Class<?> anchor) {
        Objects.requireNonNull(anchor, "anchor");
        try {
            Path codeSource = Path.of(anchor.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(codeSource)
                    || !"RingLog.jar".equalsIgnoreCase(codeSource.getFileName().toString())) {
                throw new IllegalStateException(
                        "La copia portátil debe iniciarse desde su RingLog.jar empaquetado."
                );
            }
            PortableAppPaths paths = new PortableAppPaths(codeSource.getParent());
            if (!Files.isRegularFile(paths.root.resolve(INFO_FILE))) {
                throw new IllegalStateException(
                        "No se encontró la información técnica de la copia portátil."
                );
            }
            return paths;
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(
                    "No se pudo determinar la ubicación de la copia portátil.", exception
            );
        }
    }

    public Path portableRoot() {
        return root;
    }

    @Override
    public Path dataRoot() {
        return root;
    }

    @Override
    public Path databasePath() {
        return root.resolve("data").resolve("ringlog.db");
    }

    @Override
    public Path photosDirectory() {
        return root.resolve("photos");
    }

    @Override
    public Path eventPhotosDirectory() {
        return photosDirectory().resolve("events");
    }

    @Override
    public Path nativePhotosDirectory() {
        return photosDirectory().resolve("native");
    }

    @Override
    public Path unassignedPhotosDirectory() {
        return root.resolve("unassigned-photos");
    }

    @Override
    public Path logsDirectory() {
        return root.resolve("logs");
    }

    public Path configDirectory() {
        return root.resolve("config");
    }

    public Path preferencesPath() {
        return configDirectory().resolve("preferences.properties");
    }

    public Path manifestPath() {
        return root.resolve("portable-manifest.json");
    }

    public Path infoPath() {
        return root.resolve(INFO_FILE);
    }

    private static Path normalizeRoot(Path value) {
        Path normalized = Objects.requireNonNull(value, "root")
                .toAbsolutePath().normalize();
        if (normalized.getParent() == null) {
            throw new IllegalArgumentException(
                    "La raíz de una copia portátil no puede ser la raíz de la unidad."
            );
        }
        return normalized;
    }
}
