package com.adelylria.ringlog.importexport.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.adelylria.ringlog.importexport.legacy.LegacyImportModel;
import com.adelylria.ringlog.storage.AppPaths;

/** Stages, verifies, publishes, and rolls back imported binary content. */
final class PhotoStorageService implements AutoCloseable {

    private final Path eventRoot;
    private final Path unassignedRoot;
    private final Path stagingRoot;
    private final Map<Object, StagedBinary> staged = new HashMap<>();
    private final List<Path> createdFiles = new ArrayList<>();

    private PhotoStorageService(Path eventRoot, Path unassignedRoot, Path stagingRoot) {
        this.eventRoot = eventRoot;
        this.unassignedRoot = unassignedRoot;
        this.stagingRoot = stagingRoot;
    }

    static PhotoStorageService stage(AppPaths paths, LegacyImportModel model) throws IOException {
        Path eventRoot = paths.eventPhotosDirectory().toAbsolutePath().normalize();
        Path unassignedRoot = paths.unassignedPhotosDirectory().toAbsolutePath().normalize();
        Files.createDirectories(eventRoot);
        Files.createDirectories(unassignedRoot);
        Files.createDirectories(paths.migrationDirectory());
        Path staging = Files.createTempDirectory(
                paths.migrationDirectory(), ".ringlog-import-"
        );
        PhotoStorageService storage = new PhotoStorageService(
                eventRoot, unassignedRoot, staging
        );
        try {
            for (LegacyImportModel.PhotoRow photo : model.photos()) {
                storage.stageOne(photo, photo.resolvedPath(), photo.contentSha256(),
                        photo.contentSize(), eventRoot, photo.fileName());
            }
            for (LegacyImportModel.UnassignedPhotoRow photo : model.unassignedPhotos()) {
                storage.stageOne(photo, photo.resolvedPath(), photo.contentSha256(),
                        photo.contentSize(), unassignedRoot, photo.fileName());
            }
            return storage;
        } catch (IOException | RuntimeException exception) {
            try {
                storage.close();
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    Path publish(Object row) throws IOException {
        StagedBinary binary = staged.get(row);
        if (binary == null) {
            throw new IOException("No se preparó la fotografía solicitada.");
        }
        Path destination = binary.destination();
        Files.createDirectories(destination.getParent());
        if (Files.exists(destination)) {
            verify(destination, binary.sha256(), binary.size());
            Files.deleteIfExists(binary.stagedPath());
            return destination;
        }
        try {
            try {
                Files.move(binary.stagedPath(), destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(binary.stagedPath(), destination);
            }
            createdFiles.add(destination);
            verify(destination, binary.sha256(), binary.size());
            return destination;
        } catch (IOException exception) {
            if (Files.exists(destination)) {
                try {
                    verify(destination, binary.sha256(), binary.size());
                    Files.deleteIfExists(binary.stagedPath());
                    return destination;
                } catch (IOException mismatch) {
                    exception.addSuppressed(mismatch);
                }
            }
            throw exception;
        }
    }

    void rollbackPublished() {
        for (int index = createdFiles.size() - 1; index >= 0; index--) {
            Path file = createdFiles.get(index);
            try {
                Files.deleteIfExists(file);
                deleteEmptyParents(file.getParent());
            } catch (IOException ignored) {
                // Database failure remains the primary error; stale media can be reclaimed safely.
            }
        }
        createdFiles.clear();
    }

    private void stageOne(
            Object row,
            Path source,
            String sha256,
            long size,
            Path destinationRoot,
            String originalName
    ) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("No se encontró la fotografía validada: " + originalName);
        }
        String extension = extension(originalName);
        Path destination = destinationRoot
                .resolve(sha256.substring(0, 2))
                .resolve(sha256 + extension)
                .normalize();
        if (!destination.startsWith(destinationRoot)) {
            throw new IOException("La ruta de almacenamiento calculada no es segura.");
        }
        Path copy = Files.createTempFile(stagingRoot, "binary-", ".tmp");
        Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
        verify(copy, sha256, size);
        staged.put(row, new StagedBinary(copy, destination, sha256, size));
    }

    private static void verify(Path file, String expectedHash, long expectedSize)
            throws IOException {
        if (Files.size(file) != expectedSize || !sha256(file).equalsIgnoreCase(expectedHash)) {
            throw new IOException("La fotografía cambió durante la importación: " + file.getFileName());
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 no está disponible.", impossible);
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

    private static String extension(String fileName) {
        String safeName = Path.of(fileName).getFileName().toString();
        int dot = safeName.lastIndexOf('.');
        if (dot <= 0 || dot == safeName.length() - 1) {
            return "";
        }
        String extension = safeName.substring(dot).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,10}") ? extension : "";
    }

    private void deleteEmptyParents(Path directory) throws IOException {
        Path current = directory;
        Path root = current != null && current.startsWith(eventRoot)
                ? eventRoot : unassignedRoot;
        while (current != null && current.startsWith(root) && !current.equals(root)) {
            try {
                Files.deleteIfExists(current);
            } catch (java.nio.file.DirectoryNotEmptyException notEmpty) {
                return;
            }
            current = current.getParent();
        }
    }

    @Override
    public void close() throws IOException {
        if (!Files.exists(stagingRoot)) {
            return;
        }
        try (var paths = Files.walk(stagingRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record StagedBinary(Path stagedPath, Path destination, String sha256, long size) {
    }
}
