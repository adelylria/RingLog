package com.adelylria.ringlog.database;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.PrintWriter;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;

import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.jdbc.DataSourceConnectionSource;
import com.j256.ormlite.support.ConnectionSource;
import com.adelylria.ringlog.storage.AppPaths;

public final class Database {

    public static final int SCHEMA_VERSION = 4;

    private static final String SCHEMA_RESOURCE = "/database/schema.sql";
    private static final Map<String, Set<String>> REQUIRED_COLUMNS = Map.ofEntries(
            Map.entry("species", Set.of("id", "stable_key", "code", "scientific_name",
                    "common_name", "active", "created_at", "updated_at")),
            Map.entry("bird", Set.of("id", "stable_key", "ring_number", "species_id",
                    "created_at", "updated_at")),
            Map.entry("place", Set.of("id", "stable_key", "name", "locality", "latitude",
                    "longitude", "notes", "is_favorite", "is_default", "active",
                    "created_at", "updated_at")),
            Map.entry("bird_event", Set.of("id", "stable_key", "migration_key", "bird_id",
                    "event_type", "event_date", "event_time", "place_id", "location_text",
                    "sex_code", "age_euring_code", "fat_score", "muscle_score",
                    "ringer_initials", "status", "reproductive_status", "moult_intensity",
                    "moult_extension", "bird_condition", "return_status", "wing", "p3",
                    "torso", "weight", "observations", "clouds", "rain",
                    "thermal_sensation", "wind", "capture_type", "is_dead", "source_name",
                    "source_reference", "review_status", "review_note", "created_at", "updated_at")),
            Map.entry("event_photo", Set.of("id", "stable_key", "event_id", "file_name",
                    "file_path", "mime_type", "source_name", "source_reference",
                    "content_sha256", "content_size", "created_at")),
            Map.entry("import_batch", Set.of("id", "stable_key", "source_format",
                    "format_version", "source_name", "source_reference", "fingerprint",
                    "export_id", "import_mode", "imported_at")),
            Map.entry("import_metadata", Set.of("id", "import_batch_id", "metadata_key",
                    "metadata_value")),
            Map.entry("event_source_alias", Set.of("id", "stable_key", "event_id",
                    "source_name", "source_reference")),
            Map.entry("legacy_source_record", Set.of("id", "stable_key", "import_batch_id",
                    "source_name", "source_section", "source_reference", "ring_number", "raw_payload")),
            Map.entry("legacy_unassigned_photo", Set.of("id", "stable_key", "import_batch_id",
                    "file_name", "file_path", "mime_type", "content_sha256", "content_size",
                    "width", "height", "source_reference", "created_at")),
            Map.entry("migration_audit", Set.of("id", "stable_key", "import_batch_id",
                    "source_name", "source_section", "source_reference", "source_field",
                    "original_value", "original_display", "destination", "normalized_value",
                    "status", "note")),
            Map.entry("migration_conflict", Set.of("id", "stable_key", "conflict_key",
                    "import_batch_id", "event_id", "ring_number", "conflict_type", "event_type",
                    "field_name", "canonical_source_reference", "canonical_value",
                    "alternative_source_reference", "alternative_value", "canonical_snapshot",
                    "alternative_snapshot", "original_note", "status", "resolution_type",
                    "resolution_value", "resolved_at", "resolution_notes", "created_at")),
            Map.entry("migration_warning", Set.of("id", "stable_key", "import_batch_id",
                    "severity", "source", "reference", "message"))
    );
    private static final Set<String> REQUIRED_INDEXES = Set.of(
            "idx_place_single_default",
            "idx_bird_species",
            "idx_event_bird",
            "idx_event_date",
            "idx_event_type",
            "idx_event_place",
            "idx_event_bird_date",
            "idx_place_favorite",
            "idx_photo_event",
            "idx_species_stable_key",
            "idx_bird_stable_key",
            "idx_place_stable_key",
            "idx_event_stable_key",
            "idx_photo_stable_key",
            "idx_event_migration_key",
            "idx_photo_source_reference",
            "idx_photo_content_sha256",
            "idx_import_batch_stable_key",
            "idx_import_batch_fingerprint",
            "idx_import_batch_export_id",
            "idx_import_metadata_batch",
            "idx_event_source_alias_stable_key",
            "idx_event_source_alias_event",
            "idx_event_source_alias_reference",
            "idx_legacy_source_record_stable_key",
            "idx_legacy_source_record_batch",
            "idx_legacy_source_record_reference",
            "idx_legacy_unassigned_photo_stable_key",
            "idx_legacy_unassigned_photo_batch",
            "idx_legacy_unassigned_photo_hash",
            "idx_migration_audit_stable_key",
            "idx_migration_audit_batch",
            "idx_migration_audit_reference",
            "idx_migration_conflict_stable_key",
            "idx_migration_conflict_key",
            "idx_migration_conflict_open",
            "idx_migration_conflict_event",
            "idx_migration_warning_stable_key",
            "idx_migration_warning_batch",
            "idx_migration_warning_reference"
    );
    private static final Set<String> REQUIRED_TRIGGERS = Set.of(
            "trg_species_updated_at",
            "trg_bird_updated_at",
            "trg_place_updated_at",
            "trg_bird_event_updated_at"
    );

    static {
        System.setProperty(
                "com.j256.simplelogging.level",
                System.getProperty("com.j256.simplelogging.level", "WARNING")
        );
    }

    private Database() {
    }

    public static Connection getConnection() throws SQLException {
        return getConnection(AppPaths.production().databasePath().toString());
    }

    public static Connection getConnection(String databaseFile)
            throws SQLException {
        return dataSource(databaseFile, false).getConnection();
    }

    public static Connection getReadOnlyConnection(String databaseFile)
            throws SQLException {
        return configureReadOnly(dataSource(databaseFile, true).getConnection());
    }

    public static ConnectionSource getConnectionSource(String databaseFile)
            throws SQLException {
        SQLiteDataSource dataSource = dataSource(databaseFile, false);
        return new DataSourceConnectionSource(dataSource, dataSource.getUrl());
    }

    public static ConnectionSource getReadOnlyConnectionSource(String databaseFile)
            throws SQLException {
        SQLiteDataSource delegate = dataSource(databaseFile, true);
        DataSource dataSource = new ReadOnlyDataSource(delegate);
        return new DataSourceConnectionSource(dataSource, delegate.getUrl());
    }

    public static <T> T withOrm(String databaseFile, OrmOperation<T> operation)
            throws SQLException {
        ConnectionSource source = getConnectionSource(databaseFile);
        try {
            return operation.execute(source);
        } finally {
            DaoManager.unregisterDaos(source);
            source.closeQuietly();
        }
    }

    public static <T> T withReadOnlyOrm(String databaseFile, OrmOperation<T> operation)
            throws SQLException {
        ConnectionSource source = getReadOnlyConnectionSource(databaseFile);
        try {
            return operation.execute(source);
        } finally {
            DaoManager.unregisterDaos(source);
            source.closeQuietly();
        }
    }

    public static void initialize() throws SQLException {
        initialize(AppPaths.production().databasePath().toString());
    }

    public static void validate() throws SQLException {
        validate(AppPaths.production().databasePath().toString());
    }

    public static void validate(String databaseFile) throws SQLException {
        Path database = Path.of(databaseFile).toAbsolutePath().normalize();
        if (!Files.isRegularFile(database)) {
            throw new SQLException("No existe la base de datos de RingLog.");
        }
        try (Connection connection = getReadOnlyConnection(database.toString())) {
            validateSchema(connection);
        }
    }

    public static synchronized void initialize(String databaseFile)
            throws SQLException {
        Path database = Path.of(databaseFile).toAbsolutePath().normalize();
        Path parent = database.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path lockFile = initializationLock(database);
            try (FileChannel channel = FileChannel.open(
                    lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            ); FileLock initializationLock = channel.lock()) {
                if (Files.exists(database)) {
                    if (!Files.isRegularFile(database)) {
                        throw new SQLException("La ruta de RingLog no es un archivo de base de datos.");
                    }
                    validate(database.toString());
                    return;
                }

                Path staged = Files.createTempFile(
                        parent,
                        "." + database.getFileName() + ".",
                        ".tmp"
                );
                try {
                    createFromSchema(staged);
                    try {
                        Files.move(staged, database, StandardCopyOption.ATOMIC_MOVE);
                    } catch (FileAlreadyExistsException anotherProcessWon) {
                        // Another process created the database while we were creating it.
                    }
                } finally {
                    Files.deleteIfExists(staged);
                }
            }
        } catch (IOException exception) {
            throw new SQLException("No se pudo crear la base de datos de RingLog.", exception);
        }
    }

    private static void createFromSchema(Path database)
            throws SQLException, IOException {
        try (Connection connection = getConnection(database.toString())) {
            connection.setAutoCommit(false);
            try (BufferedReader reader = schemaReader();
                 Statement statement = connection.createStatement()) {
                executeSchema(reader, statement);
                validateSchema(connection);
                connection.commit();
            } catch (IOException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    private static BufferedReader schemaReader() throws IOException {
        InputStream schema = Database.class.getResourceAsStream(SCHEMA_RESOURCE);
        if (schema == null) {
            throw new IOException(
                    "No se encontró el esquema empaquetado de RingLog: "
                            + SCHEMA_RESOURCE
            );
        }
        return new BufferedReader(new InputStreamReader(schema, StandardCharsets.UTF_8));
    }

    private static SQLiteDataSource dataSource(
            String databaseFile,
            boolean readOnly
    ) {
        String url = "jdbc:sqlite:"
                + Path.of(databaseFile).toAbsolutePath().normalize();
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setReadOnly(readOnly);

        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl(url);
        return dataSource;
    }

    private static Connection configureReadOnly(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only = ON");
            statement.execute("PRAGMA temp_store = MEMORY");
        } catch (SQLException exception) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
        return connection;
    }

    private static final class ReadOnlyDataSource implements DataSource {

        private final SQLiteDataSource delegate;

        private ReadOnlyDataSource(SQLiteDataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return configureReadOnly(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return configureReadOnly(delegate.getConnection(username, password));
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public java.util.logging.Logger getParentLogger()
                throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return iface.isInstance(this) || delegate.isWrapperFor(iface);
        }
    }

    private static void executeSchema(BufferedReader reader, Statement statement)
            throws IOException, SQLException {
        StringBuilder sql = new StringBuilder();
        boolean trigger = false;
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }

            if (sql.isEmpty()) {
                trigger = trimmed.toUpperCase(Locale.ROOT).startsWith("CREATE TRIGGER");
            }
            sql.append(line).append('\n');

            boolean complete = trigger
                    ? "END;".equalsIgnoreCase(trimmed)
                    : trimmed.endsWith(";");
            if (complete) {
                statement.execute(sql.toString());
                sql.setLength(0);
                trigger = false;
            }
        }

        if (!sql.toString().isBlank()) {
            throw new SQLException("El esquema de RingLog contiene una sentencia incompleta.");
        }
    }

    private static void validateSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            if (!result.next() || result.getInt(1) != SCHEMA_VERSION) {
                throw new SQLException("Esquema incompatible: se requiere RingLog schema v"
                        + SCHEMA_VERSION + ".");
            }
        }

        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new SQLException("La base de datos de RingLog está dañada.");
            }
        }

        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (result.next()) {
                throw new SQLException("La base de datos contiene referencias no válidas.");
            }
        }

        for (Map.Entry<String, Set<String>> table : REQUIRED_COLUMNS.entrySet()) {
            Set<String> columns = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "PRAGMA table_info(\"" + table.getKey() + "\")"
                 )) {
                while (result.next()) {
                    columns.add(result.getString("name"));
                }
            }
            if (!columns.containsAll(table.getValue())) {
                Set<String> missing = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                missing.addAll(table.getValue());
                missing.removeAll(columns);
                throw new SQLException(
                        "Esquema incompatible: faltan columnas en "
                                + table.getKey() + ": " + String.join(", ", missing)
                );
            }
        }

        validateObjects(connection, "index", REQUIRED_INDEXES);
        validateObjects(connection, "trigger", REQUIRED_TRIGGERS);
        validateStableKeys(connection);
    }

    private static void validateStableKeys(Connection connection) throws SQLException {
        for (String table : Set.of("species", "bird", "place", "bird_event", "event_photo")) {
            try (Statement statement = connection.createStatement();
                 ResultSet invalid = statement.executeQuery("""
                         SELECT COUNT(*)
                         FROM %s
                         WHERE stable_key IS NULL OR trim(stable_key) = ''
                         """.formatted(table))) {
                if (!invalid.next() || invalid.getLong(1) != 0) {
                    throw new SQLException("Esquema incompatible: stable_key vacío en " + table + ".");
                }
            }
            try (Statement statement = connection.createStatement();
                 ResultSet duplicates = statement.executeQuery("""
                         SELECT stable_key
                         FROM %s
                         GROUP BY stable_key
                         HAVING COUNT(*) > 1
                         LIMIT 1
                         """.formatted(table))) {
                if (duplicates.next()) {
                    throw new SQLException("Esquema incompatible: stable_key duplicado en " + table + ".");
                }
            }
        }
    }

    private static void validateObjects(
            Connection connection,
            String type,
            Set<String> requiredNames
    ) throws SQLException {
        Set<String> found = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (java.sql.PreparedStatement statement = connection.prepareStatement("""
                SELECT name
                FROM sqlite_master
                WHERE type = ?
                """)) {
            statement.setString(1, type);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    found.add(result.getString("name"));
                }
            }
        }
        if (!found.containsAll(requiredNames)) {
            Set<String> missing = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            missing.addAll(requiredNames);
            missing.removeAll(found);
            throw new SQLException(
                    "Esquema incompatible: faltan " + type + "s: "
                            + String.join(", ", missing)
            );
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static Path initializationLock(Path database) {
        return database.resolveSibling("." + database.getFileName() + ".init.lock");
    }

    @FunctionalInterface
    public interface OrmOperation<T> {
        T execute(ConnectionSource source) throws SQLException;
    }
}
