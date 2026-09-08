package com.adelylria.ringlog.preferences;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Properties;

/** Shared, preserving access to RingLog's per-user technical preferences. */
public final class ApplicationPreferences {

    public static final String PATH_PROPERTY = "ringlog.preferences.path";

    private static final Object FILE_LOCK = new Object();

    private final Path path;

    public ApplicationPreferences(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (this.path.getParent() == null) {
            throw new IllegalArgumentException("El archivo de preferencias necesita un directorio.");
        }
    }

    public static ApplicationPreferences production() {
        return new ApplicationPreferences(preferencesPath());
    }

    public String get(String key) {
        Objects.requireNonNull(key, "key");
        synchronized (FILE_LOCK) {
            return load().getProperty(key);
        }
    }

    public void put(String key, String value) throws IOException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        synchronized (FILE_LOCK) {
            Properties properties = load();
            properties.setProperty(key, value);
            publish(properties);
        }
    }

    public Path path() {
        return path;
    }

    private Properties load() {
        Properties properties = new Properties();
        if (!Files.isRegularFile(path)) {
            return properties;
        }
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        } catch (IOException ignored) {
            // Callers receive safe defaults; the next successful write repairs the file.
        }
        return properties;
    }

    private void publish(Properties properties) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".ringlog-preferences-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(
                    temporary,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            )) {
                properties.store(output, "RingLog preferences");
            }
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path preferencesPath() {
        String override = System.getProperty(PATH_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }

        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, "RingLog", "preferences.properties");
        }

        return Path.of(
                System.getProperty("user.home"),
                ".ringlog",
                "preferences.properties"
        );
    }
}
