package com.adelylria.ringlog.portable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import com.adelylria.ringlog.application.DataMutationCoordinator;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.PortableAppPaths;
import com.adelylria.ringlog.storage.SQLiteBackupService;

/** Builds and atomically publishes one self-contained portable read-only copy. */
public final class PortableCopyService {

    public static final String DIRECTORY_NAME = "RingLog-Portatil";
    private static final long METADATA_ALLOWANCE = 1024L * 1024L;
    private static final long MINIMUM_MARGIN = 64L * 1024L * 1024L;

    private final AppPaths sourcePaths;
    private final InstalledDistribution distribution;
    private final DataMutationCoordinator mutationCoordinator;
    private final SQLiteBackupService backupService;
    private final PortableCopyValidator validator;
    private final Clock clock;
    private final UsableSpaceProvider usableSpaceProvider;
    private final Consumer<Path> beforePublish;

    public PortableCopyService(
            AppPaths sourcePaths,
            InstalledDistribution distribution,
            DataMutationCoordinator mutationCoordinator
    ) {
        this(
                sourcePaths,
                distribution,
                mutationCoordinator,
                new SQLiteBackupService(),
                new PortableCopyValidator(),
                Clock.systemUTC(),
                PortableCopyService::usableSpace,
                ignored -> { }
        );
    }

    PortableCopyService(
            AppPaths sourcePaths,
            InstalledDistribution distribution,
            DataMutationCoordinator mutationCoordinator,
            SQLiteBackupService backupService,
            PortableCopyValidator validator,
            Clock clock,
            UsableSpaceProvider usableSpaceProvider,
            Consumer<Path> beforePublish
    ) {
        this.sourcePaths = Objects.requireNonNull(sourcePaths, "sourcePaths");
        this.distribution = Objects.requireNonNull(distribution, "distribution");
        this.mutationCoordinator = Objects.requireNonNull(
                mutationCoordinator, "mutationCoordinator"
        );
        this.backupService = Objects.requireNonNull(backupService, "backupService");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.usableSpaceProvider = Objects.requireNonNull(
                usableSpaceProvider, "usableSpaceProvider"
        );
        this.beforePublish = Objects.requireNonNull(beforePublish, "beforePublish");
    }

    public PortableCopyEstimate preflight(Path destinationParent)
            throws PortableCopyException {
        Path parent = validatedParent(destinationParent);
        Path target = parent.resolve(DIRECTORY_NAME).normalize();
        validateTarget(parent, target);
        try {
            long estimated = Math.addExact(
                    Math.addExact(distributionSize(), sourceDatasetSize()),
                    METADATA_ALLOWANCE
            );
            long margin = Math.max(MINIMUM_MARGIN, estimated / 20L);
            long required = Math.addExact(estimated, margin);
            long available = usableSpaceProvider.usableSpace(parent);
            PortableCopyEstimate estimate = new PortableCopyEstimate(
                    target,
                    estimated,
                    margin,
                    required,
                    available,
                    Files.exists(target, LinkOption.NOFOLLOW_LINKS)
            );
            if (!estimate.hasEnoughSpace()) {
                throw new PortableCopyException(
                        "No hay espacio suficiente para crear y verificar la copia nueva "
                                + "sin borrar previamente la copia existente."
                );
            }
            return estimate;
        } catch (ArithmeticException | IOException exception) {
            throw new PortableCopyException(
                    "No se pudo calcular el espacio necesario para la copia portátil.",
                    exception
            );
        }
    }

    public PortableCopyResult create(
            Path destinationParent,
            boolean replaceExisting,
            Consumer<PortableCopyProgress> progress
    ) throws PortableCopyException {
        Consumer<PortableCopyProgress> updates = progress == null ? ignored -> { } : progress;
        update(updates, PortableCopyStage.CHECKING_SPACE,
                "Comprobando el espacio disponible…", 0, 0);
        PortableCopyEstimate estimate = preflight(destinationParent);
        if (estimate.replacingExistingCopy() && !replaceExisting) {
            throw new PortableCopyException(
                    "Ya existe una copia portátil. Confirma su sustitución completa."
            );
        }

        Path parent = estimate.target().getParent();
        Path staging = parent.resolve(
                ".RingLog-Portatil.creating-" + UUID.randomUUID()
        ).normalize();
        if (!staging.getParent().equals(parent)) {
            throw new PortableCopyException("La ruta temporal de publicación no es segura.");
        }

        List<PortableFileEntry> immutableFiles = new ArrayList<>();
        PortableDatasetSummary summary;
        try {
            Files.createDirectory(staging);
            createMutableDirectories(staging);
            update(updates, PortableCopyStage.COPYING_APPLICATION,
                    "Copiando RingLog y su Java incluido…", 0, estimate.estimatedCopyBytes());
            copyInstalledApplication(staging, immutableFiles);
            writeLauncher(staging, immutableFiles);

            try (DataMutationCoordinator.Lease ignored =
                         mutationCoordinator.acquireConsistentSnapshot()) {
                update(updates, PortableCopyStage.SNAPSHOTTING_DATABASE,
                        "Creando una instantánea coherente del diario…",
                        bytes(immutableFiles), estimate.estimatedCopyBytes());
                Path database = staging.resolve("data/ringlog.db");
                backupService.backup(sourcePaths.databasePath(), database);
                addEntry(staging, database, "database", immutableFiles);

                update(updates, PortableCopyStage.COPYING_MEDIA,
                        "Copiando y verificando fotografías…",
                        bytes(immutableFiles), estimate.estimatedCopyBytes());
                copyTree(
                        sourcePaths.photosDirectory(),
                        staging.resolve("photos"),
                        staging,
                        "managed-photo",
                        immutableFiles
                );
                copyTree(
                        sourcePaths.unassignedPhotosDirectory(),
                        staging.resolve("unassigned-photos"),
                        staging,
                        "unassigned-photo",
                        immutableFiles
                );
            }

            update(updates, PortableCopyStage.VERIFYING,
                    "Validando base de datos, rutas y hashes…",
                    bytes(immutableFiles), estimate.estimatedCopyBytes());
            PortableAppPaths portablePaths = new PortableAppPaths(staging);
            summary = validator.validateDataset(portablePaths);
            validator.validateHashes(staging, immutableFiles);

            PortableFileEntry databaseEntry = immutableFiles.stream()
                    .filter(entry -> "data/ringlog.db".equals(entry.path()))
                    .findFirst()
                    .orElseThrow(() -> new PortableCopyException(
                            "No se pudo identificar el snapshot portátil."
                    ));
            String manifestHash = new PortableManifestWriter().write(
                    portablePaths.manifestPath(), immutableFiles
            );
            new PortableMetadataWriter().write(
                    portablePaths.infoPath(),
                    distribution.version(),
                    Instant.now(clock),
                    summary,
                    databaseEntry.size(),
                    databaseEntry.sha256(),
                    manifestHash
            );
            markDatabaseReadOnly(portablePaths.databasePath());
            validator.validateStartup(portablePaths);
            beforePublish.accept(staging);

            update(updates, PortableCopyStage.PUBLISHING,
                    "Publicando la copia completa…",
                    bytes(immutableFiles), estimate.estimatedCopyBytes());
            publish(staging, estimate.target());
            PortableAppPaths published = new PortableAppPaths(estimate.target());
            validator.validateStartup(published);
            update(updates, PortableCopyStage.COMPLETE,
                    "Copia portátil lista.", bytes(immutableFiles), bytes(immutableFiles));
            return new PortableCopyResult(
                    estimate.target(),
                    bytes(immutableFiles),
                    immutableFiles.size(),
                    summary.events(),
                    summary.photoCount()
            );
        } catch (PortableCopyException exception) {
            cleanupStaging(staging, exception);
            throw exception;
        } catch (Exception exception) {
            cleanupStaging(staging, exception);
            throw new PortableCopyException(
                    "No se pudo completar la copia portátil. La copia anterior sigue intacta.",
                    exception
            );
        }
    }

    private void copyInstalledApplication(
            Path staging,
            List<PortableFileEntry> entries
    ) throws IOException, PortableCopyException {
        copyFile(distribution.jar(), staging.resolve("RingLog.jar"), staging, "application", entries);
        copyFile(distribution.icon(), staging.resolve("RingLog.ico"), staging, "icon", entries);
        copyFile(
                distribution.propertiesFile(),
                staging.resolve("distribution.properties"),
                staging,
                "distribution-metadata",
                entries
        );
        copyTree(distribution.libraries(), staging.resolve("libs"), staging, "library", entries);
        copyTree(distribution.runtime(), staging.resolve("runtime"), staging, "runtime", entries);
    }

    private static void writeLauncher(Path staging, List<PortableFileEntry> entries)
            throws IOException, PortableCopyException {
        Path launcher = staging.resolve("RingLog-Portatil.cmd");
        String script = "@echo off\r\n"
                + "cd /d \"%~dp0\"\r\n"
                + "start \"\" \"runtime\\bin\\javaw.exe\" "
                + "-Dringlog.mode=portable-readonly -jar \"RingLog.jar\"\r\n";
        Files.writeString(launcher, script, StandardCharsets.US_ASCII);
        if (script.matches("(?is).*[A-Z]:\\\\.*")) {
            throw new PortableCopyException("El launcher portátil contiene una ruta absoluta.");
        }
        addEntry(staging, launcher, "launcher", entries);
    }

    private static void createMutableDirectories(Path staging) throws IOException {
        Files.createDirectories(staging.resolve("data"));
        Files.createDirectories(staging.resolve("photos/events"));
        Files.createDirectories(staging.resolve("photos/native"));
        Files.createDirectories(staging.resolve("unassigned-photos"));
        Files.createDirectories(staging.resolve("config"));
        Files.createDirectories(staging.resolve("logs"));
    }

    private static void copyTree(
            Path source,
            Path destination,
            Path portableRoot,
            String kind,
            List<PortableFileEntry> entries
    ) throws IOException, PortableCopyException {
        Files.createDirectories(destination);
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(source)
                || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new PortableCopyException("La carpeta de origen no es segura: " + source);
        }
        Path normalizedSource = source.toAbsolutePath().normalize();
        try (var paths = Files.walk(source)) {
            for (Path current : paths.toList()) {
                if (Files.isSymbolicLink(current)) {
                    throw new PortableCopyException(
                            "La distribución contiene un enlace no permitido: "
                                    + current.getFileName()
                    );
                }
                Path target = destination.resolve(normalizedSource.relativize(
                        current.toAbsolutePath().normalize()
                )).normalize();
                if (!target.startsWith(destination)) {
                    throw new PortableCopyException("Una ruta de copia sale del destino permitido.");
                }
                if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
                    copyFile(current, target, portableRoot, kind, entries);
                } else {
                    throw new PortableCopyException(
                            "La copia contiene un elemento físico no compatible."
                    );
                }
            }
        }
    }

    private static void copyFile(
            Path source,
            Path destination,
            Path portableRoot,
            String kind,
            List<PortableFileEntry> entries
    ) throws IOException, PortableCopyException {
        if (Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new PortableCopyException("No se puede copiar un archivo inseguro.");
        }
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination);
        String sourceHash = PortableHashing.sha256(source);
        String destinationHash = PortableHashing.sha256(destination);
        if (Files.size(source) != Files.size(destination)
                || !sourceHash.equalsIgnoreCase(destinationHash)) {
            throw new PortableCopyException(
                    "Un archivo cambió mientras se preparaba la copia portátil."
            );
        }
        addEntry(portableRoot, destination, kind, entries);
    }

    private static void addEntry(
            Path root,
            Path file,
            String kind,
            List<PortableFileEntry> entries
    ) throws IOException, PortableCopyException {
        Path relative = root.toAbsolutePath().normalize().relativize(
                file.toAbsolutePath().normalize()
        );
        String portable = relative.toString().replace('\\', '/');
        if (portable.startsWith("../") || portable.isBlank()) {
            throw new PortableCopyException("No se pudo crear una referencia portátil segura.");
        }
        entries.add(new PortableFileEntry(
                portable,
                Files.size(file),
                PortableHashing.sha256(file),
                kind
        ));
    }

    private Path validatedParent(Path value) throws PortableCopyException {
        if (value == null) {
            throw new PortableCopyException("Selecciona dónde crear la copia portátil.");
        }
        Path parent = value.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new PortableCopyException("El destino debe ser una carpeta existente y segura.");
        }
        Path target = parent.resolve(DIRECTORY_NAME).normalize();
        if (target.startsWith(sourcePaths.dataRoot())
                || target.startsWith(distribution.root())) {
            throw new PortableCopyException(
                    "Elige un destino fuera de los datos y de la instalación de RingLog."
            );
        }
        return parent;
    }

    private static void validateTarget(Path parent, Path target) throws PortableCopyException {
        if (!target.getParent().equals(parent)) {
            throw new PortableCopyException("La carpeta final de la copia no es segura.");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(target)
                || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))) {
            throw new PortableCopyException(
                    "RingLog-Portatil ya existe pero no es una carpeta sustituible."
            );
        }
    }

    private long distributionSize() throws IOException, PortableCopyException {
        return Math.addExact(
                Math.addExact(
                        Math.addExact(Files.size(distribution.jar()), Files.size(distribution.icon())),
                        Files.size(distribution.propertiesFile())
                ),
                Math.addExact(treeSize(distribution.libraries()), treeSize(distribution.runtime()))
        );
    }

    private long sourceDatasetSize() throws IOException, PortableCopyException {
        long database = Files.size(sourcePaths.databasePath());
        Path wal = sourcePaths.databasePath().resolveSibling(
                sourcePaths.databasePath().getFileName() + "-wal"
        );
        if (Files.isRegularFile(wal, LinkOption.NOFOLLOW_LINKS)) {
            database = Math.addExact(database, Files.size(wal));
        }
        return Math.addExact(
                database,
                Math.addExact(
                        treeSize(sourcePaths.photosDirectory()),
                        treeSize(sourcePaths.unassignedPhotosDirectory())
                )
        );
    }

    private static long treeSize(Path root) throws IOException, PortableCopyException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        long total = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw new PortableCopyException(
                            "No se admiten enlaces dentro de una copia portátil."
                    );
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    total = Math.addExact(total, Files.size(path));
                }
            }
        }
        return total;
    }

    private void publish(Path staging, Path target) throws Exception {
        Path previous = target.resolveSibling(
                ".RingLog-Portatil.previous-" + UUID.randomUUID()
        );
        boolean previousMoved = false;
        boolean newMoved = false;
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                move(target, previous);
                previousMoved = true;
            }
            move(staging, target);
            newMoved = true;
            validator.validateStartup(new PortableAppPaths(target));
        } catch (Exception failure) {
            Path failed = target.resolveSibling(
                    ".RingLog-Portatil.failed-" + UUID.randomUUID()
            );
            if (newMoved && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    move(target, failed);
                } catch (Exception moveFailure) {
                    failure.addSuppressed(moveFailure);
                }
            }
            if (previousMoved && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    move(previous, target);
                } catch (Exception rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            try {
                deleteTree(failed);
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        if (previousMoved) {
            try {
                deleteTree(previous);
            } catch (IOException ignored) {
                // The new copy is already valid; retaining the old hidden copy is safer.
            }
        }
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, destination);
        }
    }

    private static void markDatabaseReadOnly(Path database) throws IOException {
        DosFileAttributeView dos = Files.getFileAttributeView(
                database, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS
        );
        if (dos != null) {
            dos.setReadOnly(true);
        }
        database.toFile().setWritable(false, false);
    }

    private static void cleanupStaging(Path staging, Exception original) {
        try {
            deleteTree(staging);
        } catch (IOException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                DosFileAttributeView dos = Files.getFileAttributeView(
                        path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS
                );
                if (dos != null && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        dos.setReadOnly(false);
                    } catch (IOException ignored) {
                        // The following delete retains the useful failure if access is denied.
                    }
                }
                Files.deleteIfExists(path);
            }
        }
    }

    private static long bytes(List<PortableFileEntry> entries) {
        long total = 0;
        for (PortableFileEntry entry : entries) {
            total += entry.size();
        }
        return total;
    }

    private static long usableSpace(Path parent) throws IOException {
        FileStore store = Files.getFileStore(parent);
        return store.getUsableSpace();
    }

    private static void update(
            Consumer<PortableCopyProgress> listener,
            PortableCopyStage stage,
            String message,
            long completed,
            long total
    ) {
        listener.accept(new PortableCopyProgress(stage, message, completed, total));
    }

    @FunctionalInterface
    interface UsableSpaceProvider {
        long usableSpace(Path destinationParent) throws IOException;
    }
}
