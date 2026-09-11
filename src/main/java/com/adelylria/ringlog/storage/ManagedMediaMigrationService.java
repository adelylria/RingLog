package com.adelylria.ringlog.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.database.DatabaseIntegrityValidator;
import com.adelylria.ringlog.database.DatabaseUpgradeService;

/** Shared staged engine that converts legacy physical media paths into managed references. */
public final class ManagedMediaMigrationService {

    private static final List<String> COUNTED_TABLES = List.of(
            "species", "bird", "place", "bird_event", "event_photo",
            "import_batch", "legacy_unassigned_photo", "migration_conflict"
    );

    private final SQLiteBackupService backupService;
    private final DatabaseUpgradeService upgradeService;

    public ManagedMediaMigrationService() {
        this(new SQLiteBackupService(), DatabaseUpgradeService.forLegacyStaging());
    }

    ManagedMediaMigrationService(
            SQLiteBackupService backupService,
            DatabaseUpgradeService upgradeService
    ) {
        this.backupService = Objects.requireNonNull(backupService, "backupService");
        this.upgradeService = Objects.requireNonNull(upgradeService, "upgradeService");
    }

    /** Converts an already managed v3 database without publishing it before its media. */
    public MigrationResult migrateManaged(
            AppPaths managedPaths,
            MigrationProgressListener progressListener
    ) throws SQLException, IOException {
        AppPaths managed = Objects.requireNonNull(managedPaths, "managedPaths");
        Path sourceDatabase = normalizedSource(managed.databasePath());
        String sourceFingerprint = identity(sourceDatabase).sha256();
        Path operation = managed.migrationDirectory()
                .resolve("managed-schema-" + UUID.randomUUID())
                .toAbsolutePath().normalize();
        AppPaths converted = AppPaths.forDataRoot(operation.resolve("converted-root"));
        List<Path> publishedFiles = new ArrayList<>();

        Files.createDirectories(operation);
        MigrationResult staged = migrate(sourceDatabase, converted, progressListener);
        if (staged.status() != MigrationStatus.COMPLETED) {
            return new MigrationResult(
                    staged.status(),
                    sourceDatabase,
                    managed.databasePath(),
                    operation,
                    staged.issues(),
                    staged.migratedMediaCount()
            );
        }

        try {
            publishDirectory(
                    converted.eventPhotosDirectory(),
                    managed.eventPhotosDirectory(),
                    publishedFiles
            );
            publishDirectory(
                    converted.nativePhotosDirectory(),
                    managed.nativePhotosDirectory(),
                    publishedFiles
            );
            publishDirectory(
                    converted.unassignedPhotosDirectory(),
                    managed.unassignedPhotosDirectory(),
                    publishedFiles
            );
            validatePublishedMedia(converted.databasePath(), managed);
            if (!identity(sourceDatabase).sha256().equals(sourceFingerprint)) {
                throw new IOException(
                        "La base de datos gestionada cambió durante la migración."
                );
            }
            moveAtomicallyReplacing(converted.databasePath(), managed.databasePath());
            deleteDirectory(operation);
            return new MigrationResult(
                    MigrationStatus.COMPLETED,
                    sourceDatabase,
                    managed.databasePath(),
                    operation,
                    List.of(),
                    staged.migratedMediaCount()
            );
        } catch (SQLException | IOException | RuntimeException failure) {
            rollbackPublishedFiles(publishedFiles, failure);
            try {
                deleteDirectory(operation);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public MigrationResult migrate(
            Path sourceDatabase,
            AppPaths destinationPaths,
            MigrationProgressListener progressListener
    ) throws SQLException, IOException {
        Path source = normalizedSource(sourceDatabase);
        AppPaths destination = Objects.requireNonNull(destinationPaths, "destinationPaths");
        MigrationProgressListener listener = progressListener == null
                ? MigrationProgressListener.NONE : progressListener;
        Path destinationDatabase = destination.databasePath();
        if (Files.exists(destinationDatabase)) {
            throw new IOException("Ya existe una base de datos en la ubicación gestionada.");
        }

        Path operation = destination.migrationDirectory()
                .resolve("location-" + UUID.randomUUID()).toAbsolutePath().normalize();
        Path stagedRoot = operation.resolve("staged-root");
        AppPaths stagedPaths = AppPaths.forDataRoot(stagedRoot);
        Path stagedDatabase = stagedPaths.databasePath();
        List<Path> publishedFiles = new ArrayList<>();
        MigrationReceiptStore receiptStore = new MigrationReceiptStore(destination);
        boolean receiptPublished = false;

        Files.createDirectories(operation);
        notify(listener, MigrationStage.STAGING_CREATED, operation, stagedDatabase);
        try {
            Map<String, Long> sourceCounts = tableCounts(source);
            String sourceFingerprint = identity(source).sha256();
            backupService.backup(source, stagedDatabase);
            notify(listener, MigrationStage.SQLITE_SNAPSHOT_CREATED, operation, stagedDatabase);
            validateIntegrity(stagedDatabase);
            requireSameCounts(sourceCounts, tableCounts(stagedDatabase));

            DatabaseUpgradeService.Inspection inspection = upgradeService.inspect(
                    stagedDatabase,
                    new MediaPathResolver(stagedPaths)
            );
            if (inspection.state() == DatabaseUpgradeService.SchemaState.SQL_UPGRADE_REQUIRED) {
                upgradeService.upgradeV2ToV3(stagedDatabase);
                inspection = upgradeService.inspect(
                        stagedDatabase,
                        new MediaPathResolver(stagedPaths)
                );
            }
            if (inspection.state() != DatabaseUpgradeService.SchemaState.MEDIA_MIGRATION_REQUIRED) {
                throw new SQLException(
                        "La migración de ubicación esperaba una base de datos schema v3."
                );
            }

            Preparation preparation = prepareMedia(source, stagedDatabase, stagedPaths);
            if (!preparation.issues().isEmpty()) {
                deleteDirectory(stagedRoot);
                return new MigrationResult(
                        MigrationStatus.BLOCKED,
                        source,
                        destinationDatabase,
                        operation,
                        preparation.issues(),
                        0
                );
            }

            writeManagedReferences(stagedDatabase, preparation.media());
            validateStagedMedia(preparation.media());
            notify(listener, MigrationStage.MEDIA_STAGED, operation, stagedDatabase);

            markSchemaV4(stagedDatabase);
            notify(listener, MigrationStage.SCHEMA_V4_MARKED, operation, stagedDatabase);
            requireManagedSchemaV4(stagedDatabase, stagedPaths);
            requireSameCounts(sourceCounts, tableCounts(stagedDatabase));

            publishMedia(preparation.media(), stagedPaths, destination, publishedFiles);
            validatePublishedMedia(stagedDatabase, destination);
            notify(listener, MigrationStage.MEDIA_PUBLISHED, operation, stagedDatabase);

            if (!identity(source).sha256().equals(sourceFingerprint)) {
                throw new IOException("La base de datos de origen cambió durante la migración.");
            }
            String destinationFingerprint = identity(stagedDatabase).sha256();
            receiptStore.publish(MigrationReceipt.completed(
                    source,
                    destinationDatabase,
                    sourceFingerprint,
                    destinationFingerprint,
                    preparation.media().size()
            ));
            receiptPublished = true;

            Files.createDirectories(destinationDatabase.getParent());
            moveAtomically(stagedDatabase, destinationDatabase);
            try {
                notify(listener, MigrationStage.DATABASE_PUBLISHED, operation, stagedDatabase);
            } catch (RuntimeException progressFailure) {
                com.adelylria.ringlog.diagnostics.SafeLog.failure(
                        "migration_progress_notification", progressFailure
                );
            }
            try {
                deleteDirectory(stagedRoot);
            } catch (IOException cleanupFailure) {
                com.adelylria.ringlog.diagnostics.SafeLog.failure(
                        "migration_staging_cleanup", cleanupFailure
                );
            }
            return new MigrationResult(
                    MigrationStatus.COMPLETED,
                    source,
                    destinationDatabase,
                    operation,
                    List.of(),
                    preparation.media().size()
            );
        } catch (SQLException | IOException | RuntimeException exception) {
            rollbackPublishedFiles(publishedFiles, exception);
            if (receiptPublished && !Files.isRegularFile(destinationDatabase)) {
                try {
                    receiptStore.deleteIfExists();
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            try {
                deleteDirectory(stagedRoot);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    private static Preparation prepareMedia(
            Path sourceDatabase,
            Path stagedDatabase,
            AppPaths stagedPaths
    ) throws SQLException, IOException {
        List<PreparedMedia> media = new ArrayList<>();
        List<MigrationIssue> issues = new ArrayList<>();
        try (Connection connection = Database.getReadOnlyConnection(stagedDatabase.toString())) {
            readMediaRows(connection, "event_photo", false, sourceDatabase, stagedPaths,
                    media, issues);
            readMediaRows(connection, "legacy_unassigned_photo", true, sourceDatabase,
                    stagedPaths, media, issues);
        }
        return new Preparation(List.copyOf(media), List.copyOf(issues));
    }

    private static void readMediaRows(
            Connection connection,
            String table,
            boolean unassigned,
            Path sourceDatabase,
            AppPaths stagedPaths,
            List<PreparedMedia> media,
            List<MigrationIssue> issues
    ) throws SQLException, IOException {
        String sql = "SELECT id, stable_key, file_name, file_path, content_sha256, "
                + "content_size FROM " + table + " WHERE file_path IS NOT NULL";
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                long id = rows.getLong("id");
                String stableKey = rows.getString("stable_key");
                String original = rows.getString("file_path");
                LocatedMedia located = locateLegacyMedia(sourceDatabase, original);
                if (located.status() != MediaStatus.PRESENT) {
                    issues.add(new MigrationIssue(
                            table,
                            stableKey,
                            original,
                            located.status(),
                            located.candidates(),
                            located.message()
                    ));
                    continue;
                }

                Path physical = located.candidates().get(0);
                FileIdentity identity = identity(physical);
                String expectedHash = normalizedHash(rows.getString("content_sha256"));
                long expectedSize = rows.getLong("content_size");
                boolean hasExpectedSize = !rows.wasNull();
                if (expectedHash != null && !expectedHash.equals(identity.sha256())) {
                    issues.add(new MigrationIssue(
                            table, stableKey, original, MediaStatus.HASH_MISMATCH,
                            located.candidates(), "El contenido no coincide con su SHA-256 guardado."
                    ));
                    continue;
                }
                if (hasExpectedSize && expectedSize != identity.size()) {
                    issues.add(new MigrationIssue(
                            table, stableKey, original, MediaStatus.HASH_MISMATCH,
                            located.candidates(), "El tamaño no coincide con el valor guardado."
                    ));
                    continue;
                }

                String extension = safeExtension(rows.getString("file_name"), physical);
                Path stagedFile;
                String managedReference;
                MediaPathResolver stagedResolver = new MediaPathResolver(stagedPaths);
                if (unassigned) {
                    stagedFile = contentAddressedPath(
                            stagedPaths.unassignedPhotosDirectory(), identity.sha256(), extension
                    );
                    managedReference = stagedResolver.toUnassignedReference(stagedFile);
                } else {
                    PhotoArea area = eventPhotoArea(original);
                    Path root = area == PhotoArea.EVENTS
                            ? stagedPaths.eventPhotosDirectory()
                            : stagedPaths.nativePhotosDirectory();
                    stagedFile = contentAddressedPath(root, identity.sha256(), extension);
                    managedReference = stagedResolver.toEventReference(area, stagedFile);
                }
                copyVerified(physical, stagedFile, identity);
                media.add(new PreparedMedia(
                        table, id, original, physical, stagedFile, managedReference, identity
                ));
            }
        }
    }

    private static LocatedMedia locateLegacyMedia(Path sourceDatabase, String original) {
        if (original == null || original.isBlank() || original.indexOf('\0') >= 0) {
            return new LocatedMedia(
                    MediaStatus.UNSAFE_REFERENCE, List.of(), "La referencia física no es válida."
            );
        }
        Path parsed;
        try {
            parsed = Path.of(original.strip());
        } catch (RuntimeException invalidPath) {
            return new LocatedMedia(
                    MediaStatus.UNSAFE_REFERENCE, List.of(), "La referencia física no es válida."
            );
        }

        Set<Path> possible = new LinkedHashSet<>();
        if (parsed.isAbsolute()) {
            possible.add(parsed.toAbsolutePath().normalize());
        } else {
            for (Path segment : parsed) {
                if ("..".equals(segment.toString()) || ".".equals(segment.toString())) {
                    return new LocatedMedia(
                            MediaStatus.UNSAFE_REFERENCE,
                            List.of(),
                            "La referencia relativa contiene navegación no segura."
                    );
                }
            }
            Path sourceDirectory = sourceDatabase.toAbsolutePath().normalize().getParent();
            possible.add(sourceDirectory.resolve(parsed).normalize());
            possible.add(sourceDirectory.resolve("ringlog-data").resolve("photos")
                    .resolve(parsed).normalize());
            possible.add(sourceDirectory.resolve("photos").resolve(parsed).normalize());
            possible.add(sourceDirectory.resolve("ringlog-data")
                    .resolve("unassigned-photos").resolve(parsed).normalize());
        }

        List<Path> matches = possible.stream()
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> !Files.isSymbolicLink(path))
                .toList();
        if (matches.isEmpty()) {
            return new LocatedMedia(
                    MediaStatus.MISSING, List.copyOf(possible),
                    "No se ha encontrado el archivo físico referenciado."
            );
        }
        if (matches.size() > 1 && !allFilesEqual(matches)) {
            return new LocatedMedia(
                    MediaStatus.AMBIGUOUS, matches,
                    "La referencia coincide con varios archivos de contenido distinto."
            );
        }
        return new LocatedMedia(MediaStatus.PRESENT, List.of(matches.get(0)), null);
    }

    private static boolean allFilesEqual(List<Path> files) {
        try {
            FileIdentity first = identity(files.get(0));
            for (Path file : files.subList(1, files.size())) {
                if (!first.equals(identity(file))) {
                    return false;
                }
            }
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static void writeManagedReferences(Path database, List<PreparedMedia> media)
            throws SQLException {
        try (Connection connection = Database.getConnection(database.toString())) {
            connection.setAutoCommit(false);
            try {
                for (PreparedMedia item : media) {
                    String sql = "UPDATE " + item.table()
                            + " SET file_path = ?, content_sha256 = ?, content_size = ? WHERE id = ?";
                    try (PreparedStatement update = connection.prepareStatement(sql)) {
                        update.setString(1, item.managedReference());
                        update.setString(2, item.identity().sha256());
                        update.setLong(3, item.identity().size());
                        update.setLong(4, item.id());
                        if (update.executeUpdate() != 1) {
                            throw new SQLException("No se pudo convertir una referencia de medios.");
                        }
                    }
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void markSchemaV4(Path database) throws SQLException {
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 4");
        }
    }

    private static void validateStagedMedia(List<PreparedMedia> media) throws IOException {
        for (PreparedMedia item : media) {
            requireIdentity(item.stagedFile(), item.identity());
        }
    }

    private static void publishMedia(
            List<PreparedMedia> media,
            AppPaths stagedPaths,
            AppPaths destinationPaths,
            List<Path> publishedFiles
    ) throws IOException {
        for (PreparedMedia item : media) {
            Path destinationFile = publishedPath(item, stagedPaths, destinationPaths);
            Files.createDirectories(destinationFile.getParent());
            if (Files.exists(destinationFile)) {
                requireIdentity(destinationFile, item.identity());
                Files.deleteIfExists(item.stagedFile());
                continue;
            }
            moveAtomically(item.stagedFile(), destinationFile);
            publishedFiles.add(destinationFile);
            requireIdentity(destinationFile, item.identity());
        }
    }

    private static void publishDirectory(
            Path sourceRoot,
            Path destinationRoot,
            List<Path> publishedFiles
    ) throws IOException {
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }
        try (var files = Files.walk(sourceRoot)) {
            for (Path source : files.filter(path ->
                            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                    && !Files.isSymbolicLink(path))
                    .toList()) {
                Path relative = sourceRoot.toAbsolutePath().normalize()
                        .relativize(source.toAbsolutePath().normalize());
                Path destination = destinationRoot.toAbsolutePath().normalize()
                        .resolve(relative).normalize();
                if (!destination.startsWith(destinationRoot.toAbsolutePath().normalize())) {
                    throw new IOException("La publicación de medios saldría del directorio.");
                }
                Files.createDirectories(destination.getParent());
                FileIdentity expected = identity(source);
                if (Files.exists(destination)) {
                    requireIdentity(destination, expected);
                    continue;
                }
                moveAtomically(source, destination);
                publishedFiles.add(destination);
                requireIdentity(destination, expected);
            }
        }
    }

    private static Path publishedPath(
            PreparedMedia item,
            AppPaths stagedPaths,
            AppPaths destinationPaths
    ) {
        Path sourceRoot;
        Path destinationRoot;
        if ("legacy_unassigned_photo".equals(item.table())) {
            sourceRoot = stagedPaths.unassignedPhotosDirectory();
            destinationRoot = destinationPaths.unassignedPhotosDirectory();
        } else if (item.managedReference().startsWith("native/")) {
            sourceRoot = stagedPaths.nativePhotosDirectory();
            destinationRoot = destinationPaths.nativePhotosDirectory();
        } else {
            sourceRoot = stagedPaths.eventPhotosDirectory();
            destinationRoot = destinationPaths.eventPhotosDirectory();
        }
        Path relative = sourceRoot.toAbsolutePath().normalize()
                .relativize(item.stagedFile().toAbsolutePath().normalize());
        Path result = destinationRoot.toAbsolutePath().normalize().resolve(relative).normalize();
        if (!result.startsWith(destinationRoot.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("La publicación de medios saldría del directorio.");
        }
        return result;
    }

    private static void validatePublishedMedia(Path stagedDatabase, AppPaths destination)
            throws SQLException, IOException {
        MediaPathResolver resolver = new MediaPathResolver(destination);
        try (Connection connection = Database.getReadOnlyConnection(stagedDatabase.toString())) {
            validatePublishedRows(connection, "event_photo", false, resolver);
            validatePublishedRows(connection, "legacy_unassigned_photo", true, resolver);
        }
    }

    private static void validatePublishedRows(
            Connection connection,
            String table,
            boolean unassigned,
            MediaPathResolver resolver
    ) throws SQLException, IOException {
        String sql = "SELECT file_path, content_sha256, content_size FROM " + table
                + " WHERE file_path IS NOT NULL";
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                String reference = rows.getString("file_path");
                Path file = unassigned
                        ? resolver.resolveUnassignedPhoto(reference)
                        : resolver.resolveEventPhoto(reference);
                String hash = normalizedHash(rows.getString("content_sha256"));
                long size = rows.getLong("content_size");
                if (hash == null || rows.wasNull()) {
                    throw new IOException("Un medio gestionado no conserva hash y tamaño.");
                }
                requireIdentity(file, new FileIdentity(hash, size));
            }
        }
    }

    private static void requireManagedSchemaV4(Path database, AppPaths paths)
            throws SQLException, IOException {
        try (Connection connection = Database.getReadOnlyConnection(database.toString())) {
            new DatabaseIntegrityValidator().requireValid(connection, 4);
        }
        validatePublishedMedia(database, paths);
    }

    private static void validateIntegrity(Path database) throws SQLException {
        try (Connection connection = Database.getReadOnlyConnection(database.toString());
             Statement statement = connection.createStatement()) {
            try (ResultSet check = statement.executeQuery("PRAGMA quick_check")) {
                if (!check.next() || !"ok".equalsIgnoreCase(check.getString(1))) {
                    throw new SQLException("El snapshot SQLite no supera quick_check.");
                }
            }
            try (ResultSet foreignKeys = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (foreignKeys.next()) {
                    throw new SQLException("El snapshot SQLite contiene claves foráneas inválidas.");
                }
            }
        }
    }

    private static Map<String, Long> tableCounts(Path database) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection connection = Database.getReadOnlyConnection(database.toString());
             Statement statement = connection.createStatement()) {
            for (String table : COUNTED_TABLES) {
                try (PreparedStatement exists = connection.prepareStatement("""
                        SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?
                        """)) {
                    exists.setString(1, table);
                    try (ResultSet found = exists.executeQuery()) {
                        if (!found.next()) {
                            continue;
                        }
                    }
                }
                try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    if (!result.next()) {
                        throw new SQLException("No se pudo contar la tabla " + table + '.');
                    }
                    counts.put(table, result.getLong(1));
                }
            }
        }
        return Map.copyOf(counts);
    }

    private static void requireSameCounts(Map<String, Long> expected, Map<String, Long> actual)
            throws SQLException {
        for (Map.Entry<String, Long> entry : expected.entrySet()) {
            if (!Objects.equals(entry.getValue(), actual.get(entry.getKey()))) {
                throw new SQLException(
                        "El staging no conserva el recuento de " + entry.getKey() + '.'
                );
            }
        }
    }

    private static void copyVerified(Path source, Path destination, FileIdentity identity)
            throws IOException {
        Files.createDirectories(destination.getParent());
        if (Files.exists(destination)) {
            requireIdentity(destination, identity);
            return;
        }
        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
        try {
            requireIdentity(destination, identity);
        } catch (IOException failure) {
            Files.deleteIfExists(destination);
            throw failure;
        }
    }

    private static void requireIdentity(Path file, FileIdentity expected) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw new IOException("No existe el medio gestionado esperado: " + file);
        }
        FileIdentity actual = identity(file);
        if (!actual.equals(expected)) {
            throw new IOException("El medio gestionado no coincide con su hash y tamaño.");
        }
    }

    private static FileIdentity identity(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java no proporciona SHA-256.", impossible);
        }
        long size = 0;
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                size += read;
            }
        }
        return new FileIdentity(HexFormat.of().formatHex(digest.digest()), size);
    }

    private static Path contentAddressedPath(Path root, String hash, String extension) {
        return root.resolve(hash.substring(0, 2)).resolve(hash + extension)
                .toAbsolutePath().normalize();
    }

    private static String safeExtension(String fileName, Path physical) {
        String candidate = fileName == null || fileName.isBlank()
                ? physical.getFileName().toString() : fileName;
        int dot = candidate.lastIndexOf('.');
        if (dot <= 0 || dot == candidate.length() - 1) {
            return "";
        }
        String extension = candidate.substring(dot).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,10}") ? extension : "";
    }

    private static PhotoArea eventPhotoArea(String originalReference) {
        String normalized = originalReference.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("/native/") || normalized.contains("/backup/")
                || normalized.startsWith("native/") || normalized.startsWith("backup/")
                ? PhotoArea.NATIVE : PhotoArea.EVENTS;
    }

    private static String normalizedHash(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String hash = value.strip().toLowerCase(Locale.ROOT);
        return hash.matches("[0-9a-f]{64}") ? hash : null;
    }

    private static Path normalizedSource(Path sourceDatabase) throws SQLException {
        if (sourceDatabase == null) {
            throw new SQLException("No se ha indicado la base de datos anterior.");
        }
        Path source = sourceDatabase.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(source)) {
            throw new SQLException("La base de datos anterior no es un archivo seguro.");
        }
        return source;
    }

    private static void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException(
                    "El sistema no permite publicar los datos de RingLog de forma atómica.",
                    unsupported
            );
        }
    }

    private static void moveAtomicallyReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException(
                    "El sistema no permite publicar la base de RingLog de forma atómica.",
                    unsupported
            );
        }
    }

    private static void rollbackPublishedFiles(List<Path> published, Exception original) {
        for (int index = published.size() - 1; index >= 0; index--) {
            try {
                Files.deleteIfExists(published.get(index));
            } catch (IOException cleanupFailure) {
                original.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void deleteDirectory(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void notify(
            MigrationProgressListener listener,
            MigrationStage stage,
            Path operation,
            Path stagingDatabase
    ) {
        listener.onProgress(new MigrationProgress(stage, operation, stagingDatabase));
    }

    private record FileIdentity(String sha256, long size) {
    }

    private record LocatedMedia(
            MediaStatus status,
            List<Path> candidates,
            String message
    ) {
    }

    private record PreparedMedia(
            String table,
            long id,
            String originalReference,
            Path sourceFile,
            Path stagedFile,
            String managedReference,
            FileIdentity identity
    ) {
    }

    private record Preparation(
            List<PreparedMedia> media,
            List<MigrationIssue> issues
    ) {
    }
}
