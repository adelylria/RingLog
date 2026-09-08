package com.adelylria.ringlog.portable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.database.ReadOnlyDatabaseAccess;
import com.adelylria.ringlog.storage.MediaPathResolver;
import com.adelylria.ringlog.storage.PortableAppPaths;

/** Fast startup validation plus optional full creation-time hash validation. */
public final class PortableCopyValidator {

    public PortableDatasetSummary validateStartup(PortableAppPaths paths)
            throws PortableCopyException {
        requireFile(paths.portableRoot().resolve("RingLog.jar"));
        requireFile(paths.portableRoot().resolve("RingLog.ico"));
        requireFile(paths.portableRoot().resolve("RingLog-Portatil.cmd"));
        requireFile(paths.infoPath());
        requireFile(paths.manifestPath());
        requireFile(paths.portableRoot().resolve("runtime/bin/javaw.exe"));
        requireDirectory(paths.portableRoot().resolve("libs"));
        requireFile(paths.databasePath());
        return validateDataset(paths);
    }

    PortableDatasetSummary validateDataset(PortableAppPaths paths)
            throws PortableCopyException {
        try {
            Database.validate(paths.databasePath().toString());
            ReadOnlyDatabaseAccess access = new ReadOnlyDatabaseAccess(paths.databasePath());
            try (Connection connection = access.openConnection()) {
                requireQueryOnly(connection);
                validateReferences(connection, paths);
                return summary(connection);
            }
        } catch (SQLException | IllegalArgumentException exception) {
            throw new PortableCopyException(
                    "La base de datos de la copia portátil no es válida.", exception
            );
        }
    }

    void validateHashes(Path root, List<PortableFileEntry> entries)
            throws PortableCopyException {
        for (PortableFileEntry entry : entries) {
            Path file = root.resolve(entry.path()).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new PortableCopyException(
                        "Falta un archivo de la copia portátil: " + entry.path()
                );
            }
            try {
                if (Files.size(file) != entry.size()
                        || !PortableHashing.sha256(file).equalsIgnoreCase(entry.sha256())) {
                    throw new PortableCopyException(
                            "Un archivo no superó la verificación: " + entry.path()
                    );
                }
            } catch (IOException exception) {
                throw new PortableCopyException(
                        "No se pudo verificar " + entry.path(), exception
                );
            }
        }
    }

    private static void validateReferences(Connection connection, PortableAppPaths paths)
            throws SQLException, PortableCopyException {
        MediaPathResolver resolver = new MediaPathResolver(paths);
        validateReferenceQuery(
                connection,
                "SELECT file_path FROM event_photo",
                true,
                resolver
        );
        validateReferenceQuery(
                connection,
                "SELECT file_path FROM legacy_unassigned_photo",
                false,
                resolver
        );
    }

    private static void validateReferenceQuery(
            Connection connection,
            String sql,
            boolean eventPhoto,
            MediaPathResolver resolver
    ) throws SQLException, PortableCopyException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                String reference = rows.getString(1);
                if (!eventPhoto && (reference == null || reference.isBlank())) {
                    continue;
                }
                Path file = eventPhoto
                        ? resolver.resolveEventPhoto(reference)
                        : resolver.resolveUnassignedPhoto(reference);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new PortableCopyException(
                            "No se encontró una fotografía requerida por la copia portátil."
                    );
                }
            }
        }
    }

    private static PortableDatasetSummary summary(Connection connection) throws SQLException {
        return new PortableDatasetSummary(
                count(connection, "species"),
                count(connection, "bird"),
                count(connection, "place"),
                count(connection, "bird_event"),
                count(connection, "event_photo"),
                count(connection, "legacy_unassigned_photo")
        );
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.next() ? result.getLong(1) : 0;
        }
    }

    private static void requireQueryOnly(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA query_only")) {
            if (!result.next() || result.getInt(1) != 1) {
                throw new SQLException("SQLite no está en modo query_only.");
            }
        }
    }

    private static void requireFile(Path file) throws PortableCopyException {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new PortableCopyException(
                    "La copia portátil está incompleta: " + file.getFileName()
            );
        }
    }

    private static void requireDirectory(Path directory) throws PortableCopyException {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new PortableCopyException(
                    "La copia portátil está incompleta: " + directory.getFileName()
            );
        }
    }
}
