package com.adelylria.ringlog.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Performs the one supported in-place upgrade: structurally detected v2 to v3. */
final class SchemaMigrator {

    private static final Map<String, Set<String>> V2_COLUMNS = Map.of(
            "species", Set.of("id", "code", "scientific_name", "common_name", "active",
                    "created_at", "updated_at"),
            "bird", Set.of("id", "ring_number", "species_id", "created_at", "updated_at"),
            "place", Set.of("id", "name", "locality", "latitude", "longitude", "notes",
                    "is_favorite", "is_default", "active", "created_at", "updated_at"),
            "bird_event", Set.of("id", "bird_id", "event_type", "event_date", "event_time",
                    "place_id", "location_text", "sex_code", "age_euring_code", "fat_score",
                    "muscle_score", "ringer_initials", "status", "reproductive_status",
                    "moult_intensity", "moult_extension", "bird_condition", "return_status",
                    "wing", "p3", "torso", "weight", "observations", "clouds", "rain",
                    "thermal_sensation", "wind", "capture_type", "is_dead", "source_name",
                    "source_reference", "created_at", "updated_at"),
            "event_photo", Set.of("id", "event_id", "file_name", "file_path", "mime_type",
                    "created_at")
    );

    private SchemaMigrator() {
    }

    static void migrateV2ToV3(Connection connection) throws SQLException {
        int version = userVersion(connection);
        if (version == 3) {
            return;
        }

        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            applyV2ToV3(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = 3");
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            rollback(connection, exception);
            throw exception;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /** Applies only the SQL mutations; transaction and target version belong to the orchestrator. */
    static void applyV2ToV3(Connection connection) throws SQLException {
        int version = userVersion(connection);
        if (version != 0 || !hasExactV2CoreTables(connection)) {
            throw new SQLException("Esquema de RingLog no compatible: versión " + version
                    + ". No se modificó la base de datos existente.");
        }

        addV3Columns(connection);
        dropUpdatedAtTriggers(connection);
        assignStableKeys(connection, "species");
        assignStableKeys(connection, "bird");
        assignStableKeys(connection, "place");
        assignStableKeys(connection, "bird_event");
        assignStableKeys(connection, "event_photo");
        rebuildCoreTables(connection);
        createUpdatedAtTriggers(connection);
        createTraceabilityTables(connection);
        createIndexes(connection);
    }

    private static int userVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            if (!result.next()) {
                throw new SQLException("No se pudo leer la versión del esquema de RingLog.");
            }
            return result.getInt(1);
        }
    }

    static boolean hasExactV2CoreTables(Connection connection) throws SQLException {
        for (Map.Entry<String, Set<String>> table : V2_COLUMNS.entrySet()) {
            Set<String> found = new java.util.HashSet<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("PRAGMA table_info(" + table.getKey() + ")")) {
                while (result.next()) {
                    found.add(result.getString("name"));
                }
            }
            if (!found.equals(table.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static void addV3Columns(Connection connection) throws SQLException {
        executeAll(connection, List.of(
                "ALTER TABLE species ADD COLUMN stable_key TEXT",
                "ALTER TABLE bird ADD COLUMN stable_key TEXT",
                "ALTER TABLE place ADD COLUMN stable_key TEXT",
                "ALTER TABLE bird_event ADD COLUMN stable_key TEXT",
                "ALTER TABLE bird_event ADD COLUMN migration_key TEXT",
                "ALTER TABLE bird_event ADD COLUMN review_status TEXT NOT NULL DEFAULT 'OK' "
                        + "CHECK (review_status IN ('OK', 'REVIEW'))",
                "ALTER TABLE bird_event ADD COLUMN review_note TEXT",
                "ALTER TABLE event_photo ADD COLUMN stable_key TEXT",
                "ALTER TABLE event_photo ADD COLUMN source_name TEXT",
                "ALTER TABLE event_photo ADD COLUMN source_reference TEXT",
                "ALTER TABLE event_photo ADD COLUMN content_sha256 TEXT",
                "ALTER TABLE event_photo ADD COLUMN content_size INTEGER "
                        + "CHECK (content_size IS NULL OR content_size >= 0)"
        ));
    }

    private static void assignStableKeys(Connection connection, String table) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id FROM " + table + " WHERE stable_key IS NULL OR trim(stable_key) = ''");
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE " + table + " SET stable_key = ? WHERE id = ?")) {
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    update.setString(1, UUID.randomUUID().toString());
                    update.setLong(2, rows.getLong(1));
                    update.addBatch();
                }
            }
            update.executeBatch();
        }
    }

    private static void createTraceabilityTables(Connection connection) throws SQLException {
        executeAll(connection, List.of(
                """
                CREATE TABLE import_batch (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    stable_key TEXT NOT NULL CHECK (length(trim(stable_key)) > 0),
                    source_format TEXT NOT NULL,
                    format_version TEXT NOT NULL,
                    source_name TEXT NOT NULL,
                    source_reference TEXT,
                    fingerprint TEXT,
                    export_id TEXT,
                    import_mode TEXT NOT NULL,
                    imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """,
                """
                CREATE TABLE import_metadata (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    import_batch_id INTEGER NOT NULL,
                    metadata_key TEXT NOT NULL,
                    metadata_value TEXT,
                    FOREIGN KEY (import_batch_id) REFERENCES import_batch(id) ON DELETE CASCADE,
                    UNIQUE (import_batch_id, metadata_key)
                )
                """,
                """
                CREATE TABLE event_source_alias (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    stable_key TEXT NOT NULL CHECK (length(trim(stable_key)) > 0),
                    event_id INTEGER NOT NULL,
                    source_name TEXT NOT NULL,
                    source_reference TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (event_id) REFERENCES bird_event(id) ON DELETE RESTRICT,
                    UNIQUE (event_id, source_reference)
                )
                """,
                """
                CREATE TABLE legacy_source_record (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    stable_key TEXT NOT NULL CHECK (length(trim(stable_key)) > 0),
                    import_batch_id INTEGER NOT NULL,
                    source_name TEXT,
                    source_section TEXT,
                    source_reference TEXT NOT NULL,
                    ring_number TEXT,
                    raw_payload TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (import_batch_id) REFERENCES import_batch(id) ON DELETE RESTRICT,
                    UNIQUE (import_batch_id, source_reference)
                )
                """,
                """
                CREATE TABLE legacy_unassigned_photo (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    stable_key TEXT NOT NULL CHECK (length(trim(stable_key)) > 0),
                    import_batch_id INTEGER NOT NULL,
                    file_name TEXT NOT NULL,
                    file_path TEXT,
                    mime_type TEXT,
                    content_sha256 TEXT,
                    content_size INTEGER CHECK (content_size IS NULL OR content_size >= 0),
                    width INTEGER,
                    height INTEGER,
                    source_reference TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (import_batch_id) REFERENCES import_batch(id) ON DELETE RESTRICT,
                    UNIQUE (import_batch_id, source_reference)
                )
                """,
                """
                CREATE TABLE migration_audit (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    stable_key TEXT NOT NULL CHECK (length(trim(stable_key)) > 0),
                    import_batch_id INTEGER NOT NULL,
                    source_name TEXT,
                    source_section TEXT,
                    source_reference TEXT,
                    source_field TEXT,
                    original_value TEXT,
                    original_display TEXT,
                    destination TEXT,
                    normalized_value TEXT,
                    status TEXT,
                    note TEXT,
                    FOREIGN KEY (import_batch_id) REFERENCES import_batch(id) ON DELETE RESTRICT
                )
                """,
                """
                CREATE TABLE migration_conflict (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    stable_key TEXT NOT NULL CHECK (length(trim(stable_key)) > 0),
                    conflict_key TEXT NOT NULL,
                    import_batch_id INTEGER NOT NULL,
                    event_id INTEGER,
                    ring_number TEXT,
                    conflict_type TEXT NOT NULL,
                    event_type TEXT,
                    field_name TEXT,
                    canonical_value TEXT,
                    alternative_value TEXT,
                    canonical_source_reference TEXT,
                    alternative_source_reference TEXT,
                    canonical_snapshot TEXT,
                    alternative_snapshot TEXT,
                    original_note TEXT,
                    status TEXT,
                    resolution_type TEXT,
                    resolution_value TEXT,
                    resolved_at TEXT,
                    resolution_notes TEXT,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (import_batch_id) REFERENCES import_batch(id) ON DELETE RESTRICT,
                    FOREIGN KEY (event_id) REFERENCES bird_event(id) ON DELETE SET NULL
                )
                """,
                """
                CREATE TABLE migration_warning (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    stable_key TEXT NOT NULL CHECK (length(trim(stable_key)) > 0),
                    import_batch_id INTEGER NOT NULL,
                    severity TEXT,
                    source TEXT,
                    reference TEXT,
                    message TEXT NOT NULL,
                    FOREIGN KEY (import_batch_id) REFERENCES import_batch(id) ON DELETE RESTRICT
                )
                """
        ));
    }

    private static void rebuildCoreTables(Connection connection) throws SQLException {
        executeAll(connection, List.of(
                """
                CREATE TABLE species_v3 (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    stable_key TEXT NOT NULL CHECK (length(trim(stable_key)) > 0),
                    code TEXT UNIQUE,
                    scientific_name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                    common_name TEXT,
                    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT
                )
                """,
                """
                CREATE TABLE bird_v3 (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    stable_key TEXT NOT NULL CHECK (length(trim(stable_key)) > 0),
                    ring_number TEXT NOT NULL COLLATE NOCASE UNIQUE,
                    species_id INTEGER NOT NULL,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT,
                    FOREIGN KEY (species_id) REFERENCES species_v3(id) ON UPDATE CASCADE ON DELETE RESTRICT
                )
                """,
                """
                CREATE TABLE place_v3 (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    stable_key TEXT NOT NULL CHECK (length(trim(stable_key)) > 0),
                    name TEXT NOT NULL COLLATE NOCASE,
                    locality TEXT,
                    latitude REAL CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
                    longitude REAL CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
                    notes TEXT,
                    is_favorite INTEGER NOT NULL DEFAULT 0 CHECK (is_favorite IN (0, 1)),
                    is_default INTEGER NOT NULL DEFAULT 0 CHECK (is_default IN (0, 1)),
                    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT,
                    UNIQUE (name, locality)
                )
                """,
                """
                CREATE TABLE bird_event_v3 (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    stable_key TEXT NOT NULL CHECK (length(trim(stable_key)) > 0),
                    migration_key TEXT,
                    bird_id INTEGER NOT NULL,
                    event_type TEXT NOT NULL CHECK (event_type IN ('RINGING', 'CONTROL', 'RECOVERY')),
                    event_date TEXT NOT NULL,
                    event_time TEXT,
                    place_id INTEGER,
                    location_text TEXT,
                    sex_code TEXT,
                    age_euring_code TEXT,
                    fat_score INTEGER,
                    muscle_score INTEGER,
                    ringer_initials TEXT,
                    status TEXT,
                    reproductive_status TEXT,
                    moult_intensity TEXT,
                    moult_extension TEXT,
                    bird_condition TEXT,
                    return_status TEXT,
                    wing REAL CHECK (wing IS NULL OR wing >= 0),
                    p3 REAL CHECK (p3 IS NULL OR p3 >= 0),
                    torso REAL CHECK (torso IS NULL OR torso >= 0),
                    weight REAL CHECK (weight IS NULL OR weight >= 0),
                    observations TEXT,
                    clouds TEXT,
                    rain TEXT,
                    thermal_sensation TEXT,
                    wind TEXT,
                    capture_type TEXT,
                    is_dead INTEGER NOT NULL DEFAULT 0 CHECK (is_dead IN (0, 1)),
                    source_name TEXT,
                    source_reference TEXT UNIQUE,
                    review_status TEXT NOT NULL DEFAULT 'OK' CHECK (review_status IN ('OK', 'REVIEW')),
                    review_note TEXT,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT,
                    FOREIGN KEY (bird_id) REFERENCES bird_v3(id) ON UPDATE CASCADE ON DELETE RESTRICT,
                    FOREIGN KEY (place_id) REFERENCES place_v3(id) ON UPDATE CASCADE ON DELETE SET NULL
                )
                """,
                """
                CREATE TABLE event_photo_v3 (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    stable_key TEXT NOT NULL CHECK (length(trim(stable_key)) > 0),
                    event_id INTEGER NOT NULL,
                    file_name TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    mime_type TEXT,
                    source_name TEXT,
                    source_reference TEXT,
                    content_sha256 TEXT,
                    content_size INTEGER CHECK (content_size IS NULL OR content_size >= 0),
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (event_id) REFERENCES bird_event_v3(id) ON UPDATE CASCADE ON DELETE CASCADE,
                    UNIQUE (event_id, file_path)
                )
                """,
                """
                INSERT INTO species_v3(id, stable_key, code, scientific_name, common_name, active, created_at, updated_at)
                SELECT id, stable_key, code, scientific_name, common_name, active, created_at, updated_at FROM species
                """,
                """
                INSERT INTO bird_v3(id, stable_key, ring_number, species_id, created_at, updated_at)
                SELECT id, stable_key, ring_number, species_id, created_at, updated_at FROM bird
                """,
                """
                INSERT INTO place_v3(id, stable_key, name, locality, latitude, longitude, notes, is_favorite,
                                     is_default, active, created_at, updated_at)
                SELECT id, stable_key, name, locality, latitude, longitude, notes, is_favorite,
                       is_default, active, created_at, updated_at FROM place
                """,
                """
                INSERT INTO bird_event_v3(
                    id, stable_key, migration_key, bird_id, event_type, event_date, event_time, place_id,
                    location_text, sex_code, age_euring_code, fat_score, muscle_score, ringer_initials,
                    status, reproductive_status, moult_intensity, moult_extension, bird_condition,
                    return_status, wing, p3, torso, weight, observations, clouds, rain,
                    thermal_sensation, wind, capture_type, is_dead, source_name, source_reference,
                    review_status, review_note, created_at, updated_at
                ) SELECT
                    id, stable_key, migration_key, bird_id, event_type, event_date, event_time, place_id,
                    location_text, sex_code, age_euring_code, fat_score, muscle_score, ringer_initials,
                    status, reproductive_status, moult_intensity, moult_extension, bird_condition,
                    return_status, wing, p3, torso, weight, observations, clouds, rain,
                    thermal_sensation, wind, capture_type, is_dead, source_name, source_reference,
                    review_status, review_note, created_at, updated_at
                FROM bird_event
                """,
                """
                INSERT INTO event_photo_v3(
                    id, stable_key, event_id, file_name, file_path, mime_type, source_name,
                    source_reference, content_sha256, content_size, created_at
                ) SELECT
                    id, stable_key, event_id, file_name, file_path, mime_type, source_name,
                    source_reference, content_sha256, content_size, created_at
                FROM event_photo
                """,
                "DROP TABLE event_photo",
                "DROP TABLE bird_event",
                "DROP TABLE bird",
                "DROP TABLE place",
                "DROP TABLE species",
                "ALTER TABLE species_v3 RENAME TO species",
                "ALTER TABLE bird_v3 RENAME TO bird",
                "ALTER TABLE place_v3 RENAME TO place",
                "ALTER TABLE bird_event_v3 RENAME TO bird_event",
                "ALTER TABLE event_photo_v3 RENAME TO event_photo"
        ));
    }

    private static void dropUpdatedAtTriggers(Connection connection) throws SQLException {
        executeAll(connection, List.of(
                "DROP TRIGGER IF EXISTS trg_species_updated_at",
                "DROP TRIGGER IF EXISTS trg_bird_updated_at",
                "DROP TRIGGER IF EXISTS trg_place_updated_at",
                "DROP TRIGGER IF EXISTS trg_bird_event_updated_at"
        ));
    }

    private static void createUpdatedAtTriggers(Connection connection) throws SQLException {
        executeAll(connection, List.of(
                """
                CREATE TRIGGER trg_species_updated_at
                AFTER UPDATE ON species FOR EACH ROW WHEN NEW.updated_at IS OLD.updated_at
                BEGIN
                    UPDATE species SET updated_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
                END
                """,
                """
                CREATE TRIGGER trg_bird_updated_at
                AFTER UPDATE ON bird FOR EACH ROW WHEN NEW.updated_at IS OLD.updated_at
                BEGIN
                    UPDATE bird SET updated_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
                END
                """,
                """
                CREATE TRIGGER trg_place_updated_at
                AFTER UPDATE ON place FOR EACH ROW WHEN NEW.updated_at IS OLD.updated_at
                BEGIN
                    UPDATE place SET updated_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
                END
                """,
                """
                CREATE TRIGGER trg_bird_event_updated_at
                AFTER UPDATE ON bird_event FOR EACH ROW WHEN NEW.updated_at IS OLD.updated_at
                BEGIN
                    UPDATE bird_event SET updated_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
                END
                """
        ));
    }

    private static void createIndexes(Connection connection) throws SQLException {
        executeAll(connection, List.of(
                "CREATE UNIQUE INDEX idx_place_single_default ON place(is_default) WHERE is_default = 1",
                "CREATE INDEX idx_bird_species ON bird(species_id)",
                "CREATE INDEX idx_event_bird ON bird_event(bird_id)",
                "CREATE INDEX idx_event_date ON bird_event(event_date)",
                "CREATE INDEX idx_event_type ON bird_event(event_type)",
                "CREATE INDEX idx_event_place ON bird_event(place_id)",
                "CREATE INDEX idx_event_bird_date ON bird_event(bird_id, event_date DESC, event_time DESC)",
                "CREATE INDEX idx_place_favorite ON place(is_favorite DESC, name)",
                "CREATE INDEX idx_photo_event ON event_photo(event_id)",
                "CREATE UNIQUE INDEX idx_species_stable_key ON species(stable_key)",
                "CREATE UNIQUE INDEX idx_bird_stable_key ON bird(stable_key)",
                "CREATE UNIQUE INDEX idx_place_stable_key ON place(stable_key)",
                "CREATE UNIQUE INDEX idx_event_stable_key ON bird_event(stable_key)",
                "CREATE UNIQUE INDEX idx_photo_stable_key ON event_photo(stable_key)",
                "CREATE UNIQUE INDEX idx_event_migration_key ON bird_event(migration_key) "
                        + "WHERE migration_key IS NOT NULL",
                "CREATE UNIQUE INDEX idx_photo_source_reference ON event_photo(source_name, source_reference) "
                        + "WHERE source_reference IS NOT NULL",
                "CREATE INDEX idx_photo_content_sha256 ON event_photo(content_sha256) "
                        + "WHERE content_sha256 IS NOT NULL",
                "CREATE UNIQUE INDEX idx_import_batch_stable_key ON import_batch(stable_key)",
                "CREATE UNIQUE INDEX idx_import_batch_fingerprint ON import_batch(fingerprint) "
                        + "WHERE fingerprint IS NOT NULL",
                "CREATE UNIQUE INDEX idx_import_batch_export_id ON import_batch(export_id) "
                        + "WHERE export_id IS NOT NULL",
                "CREATE UNIQUE INDEX idx_event_source_alias_stable_key ON event_source_alias(stable_key)",
                "CREATE UNIQUE INDEX idx_legacy_source_record_stable_key ON legacy_source_record(stable_key)",
                "CREATE UNIQUE INDEX idx_legacy_unassigned_photo_stable_key ON legacy_unassigned_photo(stable_key)",
                "CREATE UNIQUE INDEX idx_migration_audit_stable_key ON migration_audit(stable_key)",
                "CREATE UNIQUE INDEX idx_migration_conflict_stable_key ON migration_conflict(stable_key)",
                "CREATE UNIQUE INDEX idx_migration_conflict_key ON migration_conflict(conflict_key)",
                "CREATE UNIQUE INDEX idx_migration_warning_stable_key ON migration_warning(stable_key)",
                "CREATE INDEX idx_import_metadata_batch ON import_metadata(import_batch_id)",
                "CREATE INDEX idx_event_source_alias_event ON event_source_alias(event_id)",
                "CREATE INDEX idx_event_source_alias_reference ON event_source_alias(source_name, source_reference)",
                "CREATE INDEX idx_legacy_source_record_batch ON legacy_source_record(import_batch_id)",
                "CREATE INDEX idx_legacy_source_record_reference ON legacy_source_record(source_name, source_reference)",
                "CREATE INDEX idx_legacy_unassigned_photo_batch ON legacy_unassigned_photo(import_batch_id)",
                "CREATE INDEX idx_legacy_unassigned_photo_hash ON legacy_unassigned_photo(content_sha256)",
                "CREATE INDEX idx_migration_audit_batch ON migration_audit(import_batch_id)",
                "CREATE INDEX idx_migration_audit_reference ON migration_audit(source_name, source_reference)",
                "CREATE INDEX idx_migration_conflict_open ON migration_conflict(resolved_at) WHERE resolved_at IS NULL",
                "CREATE INDEX idx_migration_conflict_event ON migration_conflict(event_id)",
                "CREATE INDEX idx_migration_warning_batch ON migration_warning(import_batch_id)",
                "CREATE INDEX idx_migration_warning_reference ON migration_warning(source, reference)"
        ));
    }

    private static void executeAll(Connection connection, List<String> sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String command : sql) {
                statement.execute(command);
            }
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
