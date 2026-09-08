package com.adelylria.ringlog.importexport.service;

import java.io.IOException;
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

import com.adelylria.ringlog.importexport.compatibility.RingLogV3BackupModel;
import com.adelylria.ringlog.storage.AppPaths;

/** Safe staging for bytes embedded in a released RingLog backup v3 workbook. */
final class BackupPhotoStorageService implements AutoCloseable {

    private final Path root;
    private final Path stagingRoot;
    private final Map<RingLogV3BackupModel.PhotoRow, StagedBinary> staged = new HashMap<>();
    private final List<Path> createdFiles = new ArrayList<>();

    private BackupPhotoStorageService(Path root, Path stagingRoot) {
        this.root = root;
        this.stagingRoot = stagingRoot;
    }

    static BackupPhotoStorageService stage(
            AppPaths paths,
            RingLogV3BackupModel model
    ) throws IOException {
        Path root = paths.nativePhotosDirectory().toAbsolutePath().normalize();
        Files.createDirectories(root);
        Files.createDirectories(paths.migrationDirectory());
        Path staging = Files.createTempDirectory(
                paths.migrationDirectory(), ".ringlog-backup-"
        );
        BackupPhotoStorageService storage = new BackupPhotoStorageService(root, staging);
        try {
            for (RingLogV3BackupModel.PhotoRow row : model.photos()) {
                Path temporary = Files.createTempFile(staging, "photo-", ".tmp");
                byte[] content = row.content();
                Files.write(temporary, content);
                String actual = sha256(content);
                if (!actual.equalsIgnoreCase(row.contentSha256())) {
                    throw new IOException("La fotografía incrustada no coincide con su huella.");
                }
                Path destination = root.resolve(actual.substring(0, 2))
                        .resolve(actual + extension(row.fileName()))
                        .normalize();
                if (!destination.startsWith(root)) {
                    throw new IOException("La ruta calculada para la fotografía no es segura.");
                }
                storage.staged.put(
                        row,
                        new StagedBinary(temporary, destination, actual, content.length)
                );
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

    Path publish(RingLogV3BackupModel.PhotoRow row) throws IOException {
        StagedBinary binary = staged.get(row);
        if (binary == null) {
            throw new IOException("No se prepararon los bytes de la fotografía.");
        }
        Path destination = binary.destination();
        Files.createDirectories(destination.getParent());
        if (Files.exists(destination)) {
            verify(destination, binary.sha256(), binary.size());
            Files.deleteIfExists(binary.temporary());
            return destination;
        }
        try {
            try {
                Files.move(binary.temporary(), destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(binary.temporary(), destination);
            }
            createdFiles.add(destination);
            verify(destination, binary.sha256(), binary.size());
            return destination;
        } catch (IOException exception) {
            if (Files.exists(destination)) {
                verify(destination, binary.sha256(), binary.size());
                Files.deleteIfExists(binary.temporary());
                return destination;
            }
            throw exception;
        }
    }

    void rollbackPublished() {
        for (int index = createdFiles.size() - 1; index >= 0; index--) {
            try {
                Files.deleteIfExists(createdFiles.get(index));
            } catch (IOException ignored) {
                // The database rollback remains the primary failure.
            }
        }
        createdFiles.clear();
    }

    static boolean matches(Path file, String hash, long size) {
        try {
            return Files.isRegularFile(file)
                    && Files.size(file) == size
                    && sha256(Files.readAllBytes(file)).equalsIgnoreCase(hash);
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static void verify(Path file, String hash, long size) throws IOException {
        if (!matches(file, hash, size)) {
            throw new IOException("La fotografía almacenada no coincide con la copia.");
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 no está disponible.", impossible);
        }
    }

    private static String extension(String name) {
        String fileName = Path.of(name).getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return "";
        }
        String extension = fileName.substring(dot).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,10}") ? extension : "";
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

    private record StagedBinary(
            Path temporary,
            Path destination,
            String sha256,
            long size
    ) {
    }
}
