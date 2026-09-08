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

import com.adelylria.ringlog.importexport.nativeformat.NativeExportModel;
import com.adelylria.ringlog.storage.AppPaths;

/** Stages verified ZIP media before a native transaction and publishes it by content hash. */
final class NativePhotoStorageService implements AutoCloseable {

    private final Path nativeRoot;
    private final Path unassignedRoot;
    private final Path stagingRoot;
    private final Map<String, StagedBinary> staged = new HashMap<>();
    private final List<Path> created = new ArrayList<>();

    private NativePhotoStorageService(
            Path nativeRoot,
            Path unassignedRoot,
            Path stagingRoot
    ) {
        this.nativeRoot = nativeRoot;
        this.unassignedRoot = unassignedRoot;
        this.stagingRoot = stagingRoot;
    }

    static NativePhotoStorageService stage(AppPaths paths, NativeExportModel model)
            throws IOException {
        Path nativeRoot = paths.nativePhotosDirectory().toAbsolutePath().normalize();
        Path unassignedRoot = paths.unassignedPhotosDirectory().toAbsolutePath().normalize();
        Files.createDirectories(nativeRoot);
        Files.createDirectories(unassignedRoot);
        Files.createDirectories(paths.migrationDirectory());
        Path temporary = Files.createTempDirectory(
                paths.migrationDirectory(), ".ringlog-native-"
        );
        NativePhotoStorageService storage = new NativePhotoStorageService(
                nativeRoot, unassignedRoot, temporary
        );
        try {
            for (NativeExportModel.MediaFile media : model.media().values()) {
                Path staged = Files.createTempFile(temporary, "binary-", ".tmp");
                Files.copy(media.resolvedPath(), staged, StandardCopyOption.REPLACE_EXISTING);
                verify(staged, media.contentSha256(), media.contentSize());
                Path destinationRoot = media.packagePath().startsWith("photos/unassigned/")
                        ? unassignedRoot : nativeRoot;
                Path destination = destinationRoot
                        .resolve(media.contentSha256().substring(0, 2))
                        .resolve(media.contentSha256() + extension(media.packagePath()))
                        .normalize();
                if (!destination.startsWith(destinationRoot)) {
                    throw new IOException("La ruta nativa de almacenamiento no es segura.");
                }
                storage.staged.put(media.packagePath(), new StagedBinary(
                        staged, destination, media.contentSha256(), media.contentSize()
                ));
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

    Path publish(String packagePath) throws IOException {
        StagedBinary binary = staged.get(packagePath);
        if (binary == null) {
            throw new IOException("No se preparó el binario nativo " + packagePath + ".");
        }
        Files.createDirectories(binary.destination().getParent());
        if (Files.exists(binary.destination())) {
            verify(binary.destination(), binary.hash(), binary.size());
            Files.deleteIfExists(binary.temporary());
            return binary.destination();
        }
        try {
            try {
                Files.move(binary.temporary(), binary.destination(),
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(binary.temporary(), binary.destination());
            }
            created.add(binary.destination());
            verify(binary.destination(), binary.hash(), binary.size());
            return binary.destination();
        } catch (IOException exception) {
            if (Files.exists(binary.destination())) {
                verify(binary.destination(), binary.hash(), binary.size());
                Files.deleteIfExists(binary.temporary());
                return binary.destination();
            }
            throw exception;
        }
    }

    void rollbackPublished() {
        for (int index = created.size() - 1; index >= 0; index--) {
            try {
                Files.deleteIfExists(created.get(index));
            } catch (IOException ignored) {
                // Preserve the transactional database error.
            }
        }
        created.clear();
    }

    static boolean matches(Path file, String hash, long size) {
        try {
            return file != null && Files.isRegularFile(file)
                    && Files.size(file) == size
                    && sha256(file).equalsIgnoreCase(hash);
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static void verify(Path file, String expectedHash, long expectedSize)
            throws IOException {
        if (Files.size(file) != expectedSize || !sha256(file).equalsIgnoreCase(expectedHash)) {
            throw new IOException("Un binario nativo cambió durante la restauración.");
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

    private static String extension(String packagePath) {
        String name = Path.of(packagePath).getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        String extension = name.substring(dot).toLowerCase(Locale.ROOT);
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
            Path temporary, Path destination, String hash, long size
    ) {
    }
}
