package com.adelylria.ringlog.database;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.SQLiteBackupService;

/** Creates and publishes a validated pre-schema SQLite snapshot plus manifest. */
public final class SchemaBackupService {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final SQLiteBackupService sqliteBackupService;
    private final DatabaseIntegrityValidator integrityValidator;

    public SchemaBackupService() {
        this(new SQLiteBackupService(), new DatabaseIntegrityValidator());
    }

    SchemaBackupService(
            SQLiteBackupService sqliteBackupService,
            DatabaseIntegrityValidator integrityValidator
    ) {
        this.sqliteBackupService = Objects.requireNonNull(sqliteBackupService, "sqliteBackupService");
        this.integrityValidator = Objects.requireNonNull(integrityValidator, "integrityValidator");
    }

    public SchemaBackup create(
            AppPaths appPaths,
            Path sourceDatabase,
            int fromSchema,
            int targetSchema,
            String appVersion
    ) throws SchemaBackupException {
        Objects.requireNonNull(appPaths, "appPaths");
        Objects.requireNonNull(sourceDatabase, "sourceDatabase");
        Objects.requireNonNull(appVersion, "appVersion");

        Instant created = Instant.now();
        String suffix = FILE_TIME.format(created) + '-'
                + UUID.randomUUID().toString().substring(0, 8);
        String baseName = "ringlog-before-schema-v" + targetSchema + '-' + suffix;
        Path backups = appPaths.backupsDirectory().toAbsolutePath().normalize();
        Path stagedDatabase = backups.resolve('.' + baseName + ".db.tmp");
        Path stagedManifest = backups.resolve('.' + baseName + ".json.tmp");
        Path publishedDatabase = backups.resolve(baseName + ".db");
        Path publishedManifest = backups.resolve(baseName + ".json");
        boolean databasePublished = false;

        try {
            Files.createDirectories(backups);
            sqliteBackupService.backup(sourceDatabase, stagedDatabase);
            if (Files.size(stagedDatabase) == 0) {
                throw new IOException("La instantánea SQLite está vacía.");
            }
            try (Connection connection = Database.getReadOnlyConnection(stagedDatabase.toString())) {
                integrityValidator.requireValid(connection, fromSchema);
            }

            long size = Files.size(stagedDatabase);
            String sha256 = sha256(stagedDatabase);
            SchemaBackupManifest manifest = new SchemaBackupManifest(
                    fromSchema,
                    targetSchema,
                    appVersion,
                    created.toString(),
                    sha256,
                    publishedDatabase.getFileName().toString()
            );
            Files.writeString(stagedManifest, manifest.toJson(), StandardCharsets.UTF_8);

            atomicMove(stagedDatabase, publishedDatabase);
            databasePublished = true;
            atomicMove(stagedManifest, publishedManifest);
            return new SchemaBackup(
                    publishedDatabase,
                    publishedManifest,
                    fromSchema,
                    targetSchema,
                    appVersion,
                    created.toString(),
                    sha256,
                    size
            );
        } catch (Exception failure) {
            cleanup(stagedDatabase, failure);
            cleanup(stagedManifest, failure);
            if (databasePublished) {
                cleanup(publishedDatabase, failure);
            }
            cleanup(publishedManifest, failure);
            throw new SchemaBackupException(
                    "No se pudo crear y validar el backup previo al cambio de schema.",
                    failure
            );
        }
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void atomicMove(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("El sistema no permite publicar el backup de forma atómica.", unsupported);
        }
    }

    private static void cleanup(Path path, Exception original) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }
}
