package com.adelylria.ringlog.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

import org.sqlite.SQLiteConnection;

import com.adelylria.ringlog.database.Database;

/** Creates a transactionally consistent SQLite snapshot without copying live WAL files. */
public final class SQLiteBackupService {

    public void backup(Path sourceDatabase, Path destinationDatabase)
            throws SQLException, IOException {
        Path source = sourceDatabase.toAbsolutePath().normalize();
        Path destination = destinationDatabase.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new SQLException("No existe la base de datos de origen.");
        }
        if (Files.exists(destination)) {
            throw new IOException("El destino del snapshot SQLite ya existe.");
        }
        Files.createDirectories(destination.getParent());

        try (Connection connection = Database.getReadOnlyConnection(source.toString())) {
            if (!(connection instanceof SQLiteConnection sqlite)) {
                throw new SQLException("La conexión no permite usar SQLite Online Backup.");
            }
            int result = sqlite.getDatabase().backup("main", destination.toString(), null);
            if (result != 0) {
                throw new SQLException("SQLite Online Backup terminó con código " + result + '.');
            }
        } catch (SQLException | RuntimeException exception) {
            Files.deleteIfExists(destination);
            throw exception;
        }
        if (!Files.isRegularFile(destination)) {
            throw new IOException("SQLite no creó el snapshot esperado.");
        }
    }
}
