package com.adelylria.ringlog.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Looks only in RingLog's explicitly known old location; it never searches the disk. */
public final class LegacyDataLocationDetector {

    private final Path workingDirectory;
    private final AppPaths managedPaths;

    public LegacyDataLocationDetector(Path workingDirectory, AppPaths managedPaths) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath().normalize();
        this.managedPaths = Objects.requireNonNull(managedPaths, "managedPaths");
    }

    public static LegacyDataLocationDetector production(AppPaths managedPaths) {
        String userDirectory = System.getProperty("user.dir");
        if (userDirectory == null || userDirectory.isBlank()) {
            throw new IllegalStateException("Java no ha proporcionado user.dir.");
        }
        return new LegacyDataLocationDetector(Path.of(userDirectory), managedPaths);
    }

    public DataLocationAssessment detect() throws IOException {
        Path legacyPath = workingDirectory.resolve("ringlog.db").normalize();
        Path managedPath = managedPaths.databasePath().toAbsolutePath().normalize();
        DatabaseCandidate legacy = candidate(legacyPath);
        DatabaseCandidate managed = candidate(managedPath);
        DataLocationState state;
        if (legacy == null && managed == null) {
            state = DataLocationState.NO_DATABASE;
        } else if (legacy != null && managed == null) {
            state = DataLocationState.LEGACY_ONLY;
        } else if (legacy == null) {
            state = DataLocationState.MANAGED_ONLY;
        } else if (legacy.sha256().equals(managed.sha256())) {
            state = DataLocationState.BOTH_IDENTICAL;
        } else {
            state = DataLocationState.BOTH_DIFFERENT;
        }
        return new DataLocationAssessment(state, legacy, managed);
    }

    public DatabaseCandidate inspectUserSelected(Path database) throws IOException {
        DatabaseCandidate result = candidate(Objects.requireNonNull(database, "database"));
        if (result == null) {
            throw new IOException("El archivo seleccionado no es una base de datos regular.");
        }
        return result;
    }

    private static DatabaseCandidate candidate(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalized)) {
            throw new IOException("La ubicación de base de datos no es un archivo seguro: "
                    + normalized);
        }
        return new DatabaseCandidate(
                normalized,
                Files.size(normalized),
                Files.getLastModifiedTime(normalized, LinkOption.NOFOLLOW_LINKS),
                sha256(normalized)
        );
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java no proporciona SHA-256.", impossible);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
