package com.adelylria.ringlog.database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.adelylria.ringlog.database.entity.BirdEntity;
import com.adelylria.ringlog.database.entity.BirdEventEntity;
import com.adelylria.ringlog.database.entity.EventPhotoEntity;
import com.adelylria.ringlog.database.entity.PlaceEntity;
import com.adelylria.ringlog.database.entity.SpeciesEntity;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.MediaPathResolver;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;

/** Integration coverage for the v2 -> v3 SQL step and fresh v4 databases. */
public final class SchemaMigrationTest {

    private static final Set<String> V3_TABLES = Set.of(
            "species", "bird", "place", "bird_event", "event_photo",
            "import_batch", "import_metadata", "event_source_alias",
            "legacy_source_record", "legacy_unassigned_photo", "migration_audit",
            "migration_conflict", "migration_warning"
    );
    private static final Set<String> V3_INDEXES = Set.of(
            "idx_place_single_default", "idx_bird_species", "idx_event_bird",
            "idx_event_date", "idx_event_type", "idx_event_place",
            "idx_event_bird_date", "idx_place_favorite", "idx_photo_event",
            "idx_species_stable_key", "idx_bird_stable_key", "idx_place_stable_key",
            "idx_event_stable_key", "idx_photo_stable_key",
            "idx_event_migration_key", "idx_photo_source_reference",
            "idx_photo_content_sha256",
            "idx_import_batch_stable_key", "idx_import_batch_fingerprint",
            "idx_import_batch_export_id", "idx_event_source_alias_stable_key",
            "idx_import_metadata_batch", "idx_event_source_alias_event",
            "idx_event_source_alias_reference",
            "idx_legacy_source_record_stable_key", "idx_legacy_source_record_batch",
            "idx_legacy_source_record_reference",
            "idx_legacy_unassigned_photo_stable_key", "idx_legacy_unassigned_photo_batch",
            "idx_legacy_unassigned_photo_hash",
            "idx_migration_audit_stable_key", "idx_migration_audit_batch",
            "idx_migration_audit_reference",
            "idx_migration_conflict_stable_key", "idx_migration_conflict_key",
            "idx_migration_conflict_open", "idx_migration_conflict_event",
            "idx_migration_warning_stable_key", "idx_migration_warning_batch",
            "idx_migration_warning_reference"
    );
    private static final Map<String, Set<String>> V3_COLUMNS = Map.ofEntries(
            Map.entry("species", Set.of("stable_key")),
            Map.entry("bird", Set.of("stable_key")),
            Map.entry("place", Set.of("stable_key")),
            Map.entry("bird_event", Set.of("stable_key", "migration_key", "review_status", "review_note")),
            Map.entry("event_photo", Set.of(
                    "stable_key", "source_name", "source_reference", "content_sha256", "content_size"
            )),
            Map.entry("import_batch", Set.of(
                    "id", "stable_key", "source_name", "source_reference",
                    "source_format", "format_version", "fingerprint", "export_id",
                    "import_mode", "imported_at"
            )),
            Map.entry("import_metadata", Set.of("id", "import_batch_id", "metadata_key", "metadata_value")),
            Map.entry("event_source_alias", Set.of(
                    "id", "stable_key", "event_id", "source_name", "source_reference"
            )),
            Map.entry("legacy_source_record", Set.of(
                    "id", "stable_key", "import_batch_id", "source_name", "source_section",
                    "source_reference", "ring_number", "raw_payload"
            )),
            Map.entry("legacy_unassigned_photo", Set.of(
                    "id", "stable_key", "import_batch_id", "file_name", "file_path", "mime_type",
                    "content_sha256", "content_size", "width", "height", "source_reference", "created_at"
            )),
            Map.entry("migration_audit", Set.of(
                    "id", "stable_key", "import_batch_id", "source_name", "source_section",
                    "source_reference", "source_field", "original_value", "original_display",
                    "destination", "normalized_value", "status", "note"
            )),
            Map.entry("migration_conflict", Set.of(
                    "id", "stable_key", "conflict_key", "import_batch_id", "event_id", "ring_number",
                    "conflict_type", "event_type", "field_name",
                    "canonical_value", "alternative_value",
                    "canonical_source_reference", "alternative_source_reference",
                    "canonical_snapshot", "alternative_snapshot", "status", "original_note",
                    "resolution_type", "resolution_value", "resolved_at", "resolution_notes"
            )),
            Map.entry("migration_warning", Set.of(
                    "id", "stable_key", "import_batch_id", "severity", "source", "reference", "message"
            ))
    );

    private SchemaMigrationTest() {
    }

    public static void freshDatabaseIsV5AndCurrentEntitiesGenerateStableKeys()
            throws Exception {
        require(Database.SCHEMA_VERSION == 5,
                "The application must expose schema version 5 from one shared constant");
        Path directory = Files.createTempDirectory("ringlog-v5-fresh-");
        Path database = directory.resolve("ringlog.db");
        try {
            Database.initialize(database.toString());
            createRepresentativeRows(database);

            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                require(singleLong(statement, "PRAGMA user_version") == 5,
                        "A fresh database must be schema v5");
                requireColumns(statement, "place", Set.of(
                        "autonomous_community", "country"
                ));
                requireObjects(statement, "table", V3_TABLES);
                requireObjects(statement, "index", V3_INDEXES);
                requireObjects(statement, "trigger", Set.of(
                        "trg_species_updated_at", "trg_bird_updated_at",
                        "trg_place_updated_at", "trg_bird_event_updated_at"
                ));
                for (Map.Entry<String, Set<String>> entry : V3_COLUMNS.entrySet()) {
                    requireColumns(statement, entry.getKey(), entry.getValue());
                }
                requireForeignKey(statement, "import_metadata", "import_batch_id", "import_batch");
                requireForeignKey(statement, "event_source_alias", "event_id", "bird_event");
                requireForeignKey(statement, "legacy_source_record", "import_batch_id", "import_batch");
                requireForeignKey(statement, "legacy_unassigned_photo", "import_batch_id", "import_batch");
                requireNoColumn(statement, "legacy_unassigned_photo", "event_id");
                requireForeignKey(statement, "migration_audit", "import_batch_id", "import_batch");
                requireForeignKey(statement, "migration_conflict", "import_batch_id", "import_batch");
                requireForeignKey(statement, "migration_conflict", "event_id", "bird_event");
                requireForeignKey(statement, "migration_warning", "import_batch_id", "import_batch");
                for (String table : List.of(
                        "species", "bird", "place", "bird_event", "event_photo"
                )) {
                    requireUuidKeys(statement, table);
                    requireNotNullColumn(statement, table, "stable_key");
                }
                require("OK".equals(singleString(statement,
                        "SELECT review_status FROM bird_event")),
                        "New events must default to review status OK");
                requireSqlFailure(() -> statement.executeUpdate(
                        "UPDATE bird_event SET review_status = 'INVALID'"),
                        "Review status must reject values outside OK/REVIEW");
                require(singleLong(statement, "PRAGMA quick_check") == 0,
                        "quick_check should return its only OK row");
                try (ResultSet foreignKeys = statement.executeQuery("PRAGMA foreign_key_check")) {
                    require(!foreignKeys.next(), "Fresh v5 data must satisfy foreign keys");
                }
            }
            Database.validate(database.toString());
        } finally {
            deleteDirectory(directory);
        }
    }

    public static void populatedV2DatabaseMigratesWithoutLosingValues()
            throws Exception {
        Path directory = Files.createTempDirectory("ringlog-v2-migration-");
        Path database = directory.resolve("ringlog.db");
        try {
            createV2Database(database);

            new DatabaseUpgradeService().upgradeV2ToV3(database);

            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                require(singleLong(statement, "PRAGMA user_version") == 3,
                        "A migrated database must record schema v3");
                require("MIG-001".equals(singleString(statement,
                        "SELECT ring_number FROM bird")), "Bird values must survive migration");
                require("Turdus philomelos".equals(singleString(statement,
                        "SELECT scientific_name FROM species")),
                        "Species values must survive migration");
                require("2024-03-01 10:00:00".equals(singleString(statement,
                        "SELECT created_at FROM species"))
                                && "2024-03-02 11:00:00".equals(singleString(statement,
                        "SELECT updated_at FROM species")),
                        "Species timestamps must survive migration");
                require("Observatorio".equals(singleString(statement,
                        "SELECT name FROM place")), "Place values must survive migration");
                require("2024-03-01 10:05:00".equals(singleString(statement,
                        "SELECT created_at FROM bird"))
                                && "2024-03-02 11:05:00".equals(singleString(statement,
                        "SELECT updated_at FROM bird")),
                        "Bird timestamps must survive migration");
                require("2024-03-01 10:10:00".equals(singleString(statement,
                        "SELECT created_at FROM place"))
                                && "2024-03-02 11:10:00".equals(singleString(statement,
                        "SELECT updated_at FROM place")),
                        "Place timestamps must survive migration");
                try (ResultSet event = statement.executeQuery("""
                        SELECT event_type, event_date, event_time, observations,
                               source_name, source_reference, created_at, updated_at,
                               review_status, migration_key
                        FROM bird_event
                        """)) {
                    require(event.next()
                                    && "RINGING".equals(event.getString(1))
                                    && "2024-03-15".equals(event.getString(2))
                                    && "07:45".equals(event.getString(3))
                                    && "Migración de prueba".equals(event.getString(4))
                                    && "Access".equals(event.getString(5))
                                    && "ACCESS:42".equals(event.getString(6))
                                    && "2024-03-15 07:46:00".equals(event.getString(7))
                                    && "2024-03-16 08:00:00".equals(event.getString(8))
                                    && "OK".equals(event.getString(9))
                                    && event.getString(10) == null,
                            "Event values, timestamps, and independent migration key must survive");
                }
                try (ResultSet photo = statement.executeQuery("""
                        SELECT file_name, file_path, mime_type, created_at
                        FROM event_photo
                        """)) {
                    require(photo.next()
                                    && "legacy.jpg".equals(photo.getString(1))
                                    && "/archive/legacy.jpg".equals(photo.getString(2))
                                    && "image/jpeg".equals(photo.getString(3))
                                    && "2024-03-15 08:00:00".equals(photo.getString(4)),
                            "Photo values and timestamps must survive migration");
                }
                for (String table : List.of(
                        "species", "bird", "place", "bird_event", "event_photo"
                )) {
                    requireUuidKeys(statement, table);
                    requireNotNullColumn(statement, table, "stable_key");
                }
            }
            DatabaseUpgradeService.Inspection inspection = new DatabaseUpgradeService().inspect(
                    database, new MediaPathResolver(AppPaths.forDataRoot(directory))
            );
            require(inspection.state()
                            == DatabaseUpgradeService.SchemaState.MEDIA_MIGRATION_REQUIRED,
                    "The SQL-only v2 to v3 step must still require media migration");
        } finally {
            deleteDirectory(directory);
        }
    }

    public static void exactPragmaZeroV2IsDetectedAsLogicalSchemaTwo() throws Exception {
        Path directory = Files.createTempDirectory("ringlog-v2-detection-");
        Path database = directory.resolve("ringlog.db");
        try {
            createV2Database(database);

            DetectedSchema detected = new SchemaVersionDetector().detect(database);

            require(detected.pragmaVersion() == 0,
                    "The legacy v2 fixture must retain its historical PRAGMA value");
            require(detected.logicalVersion() == 2,
                    "An exact pragma-zero v2 database must be represented as logical schema 2");
        } finally {
            deleteDirectory(directory);
        }
    }

    public static void unknownOrNewerDatabasesAreRejectedWithoutRebuild()
            throws Exception {
        Path database = Files.createTempFile("ringlog-unknown-schema-", ".db");
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sentinel (value TEXT)");
            statement.execute("PRAGMA user_version = 6");
        }
        try {
            requireSqlFailure(() -> Database.initialize(database.toString()),
                    "Unknown database versions must be rejected");
            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                require(objectExists(statement, "table", "sentinel"),
                        "Rejected databases must not be rebuilt or deleted");
            }
        } finally {
            Files.deleteIfExists(database);
        }
    }

    public static void initializingV5AgainKeepsStableKeys() throws Exception {
        Path directory = Files.createTempDirectory("ringlog-v5-idempotent-");
        Path database = directory.resolve("ringlog.db");
        try {
            Database.initialize(database.toString());
            createRepresentativeRows(database);
            Map<String, String> before = stableKeys(database);

            Database.initialize(database.toString());

            require(before.equals(stableKeys(database)),
                    "Reinitializing schema v5 must not replace persistent stable keys");
        } finally {
            deleteDirectory(directory);
        }
    }

    public static void failedV2MigrationRollsBackEverySchemaChange() throws Exception {
        Path directory = Files.createTempDirectory("ringlog-v2-rollback-");
        Path database = directory.resolve("ringlog.db");
        try {
            createV2Database(database);
            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE import_batch (sentinel TEXT)");
            }

            requireSqlFailure(() -> new DatabaseUpgradeService().upgradeV2ToV3(database),
                    "A migration collision must abort the whole v2 to v3 upgrade");

            try (Connection connection = Database.getConnection(database.toString());
                 Statement statement = connection.createStatement()) {
                require(singleLong(statement, "PRAGMA user_version") == 0,
                        "A failed migration must retain the v2 user_version");
                requireNoColumn(statement, "species", "stable_key");
                require("Turdus philomelos".equals(singleString(statement,
                                "SELECT scientific_name FROM species")),
                        "A failed migration must retain existing v2 rows");
                requireColumns(statement, "import_batch", Set.of("sentinel"));
            }
        } finally {
            deleteDirectory(directory);
        }
    }

    private static Map<String, String> stableKeys(Path database) throws Exception {
        Map<String, String> keys = new java.util.LinkedHashMap<>();
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement()) {
            for (String table : List.of(
                    "species", "bird", "place", "bird_event", "event_photo"
            )) {
                keys.put(table, singleString(statement, "SELECT stable_key FROM " + table));
            }
        }
        return keys;
    }

    private static void createRepresentativeRows(Path database) throws Exception {
        Database.withOrm(database.toString(), source -> {
            Dao<SpeciesEntity, Long> species = DaoManager.createDao(source, SpeciesEntity.class);
            Dao<BirdEntity, Long> birds = DaoManager.createDao(source, BirdEntity.class);
            Dao<PlaceEntity, Long> places = DaoManager.createDao(source, PlaceEntity.class);
            Dao<BirdEventEntity, Long> events = DaoManager.createDao(source, BirdEventEntity.class);
            Dao<EventPhotoEntity, Long> photos = DaoManager.createDao(source, EventPhotoEntity.class);

            SpeciesEntity speciesEntity = new SpeciesEntity("TUR-PHI", "Turdus philomelos", "Zorzal");
            species.create(speciesEntity);
            BirdEntity bird = new BirdEntity("FRESH-001", speciesEntity.id());
            birds.create(bird);
            PlaceEntity place = new PlaceEntity("Observatorio", "Pollença", 39.87, 3.01,
                    "Prueba", true, true);
            places.create(place);
            BirdEventEntity event = new BirdEventEntity();
            event.setBirdId(bird.id());
            event.setEventType("RINGING");
            event.setEventDate("2026-08-25");
            event.setPlaceId(place.id());
            events.create(event);
            EventPhotoEntity photo = new EventPhotoEntity();
            photo.setEventId(event.id());
            photo.setFileName("fresh.jpg");
            photo.setFilePath("events/ab/fresh.jpg");
            photo.setMimeType("image/jpeg");
            photos.create(photo);
            return null;
        });
    }

    private static void createV2Database(Path database) throws Exception {
        try (Connection connection = Database.getConnection(database.toString());
             Statement statement = connection.createStatement()) {
            executeStatements(statement, V2_SCHEMA);
            statement.executeUpdate("""
                    INSERT INTO species(code, scientific_name, common_name, active, created_at, updated_at)
                    VALUES ('TUR-PHI', 'Turdus philomelos', 'Zorzal común', 1,
                            '2024-03-01 10:00:00', '2024-03-02 11:00:00')
                    """);
            statement.executeUpdate("""
                    INSERT INTO bird(ring_number, species_id, created_at, updated_at)
                    VALUES ('MIG-001', 1, '2024-03-01 10:05:00', '2024-03-02 11:05:00')
                    """);
            statement.executeUpdate("""
                    INSERT INTO place(name, locality, latitude, longitude, notes, is_favorite,
                                      is_default, active, created_at, updated_at)
                    VALUES ('Observatorio', 'Pollença', 39.87, 3.01, 'Notas v2', 1, 1, 1,
                            '2024-03-01 10:10:00', '2024-03-02 11:10:00')
                    """);
            statement.executeUpdate("""
                    INSERT INTO bird_event(bird_id, event_type, event_date, event_time, place_id,
                                           observations, is_dead, source_name, source_reference,
                                           created_at, updated_at)
                    VALUES (1, 'RINGING', '2024-03-15', '07:45', 1,
                            'Migración de prueba', 0, 'Access', 'ACCESS:42',
                            '2024-03-15 07:46:00', '2024-03-16 08:00:00')
                    """);
            statement.executeUpdate("""
                    INSERT INTO event_photo(event_id, file_name, file_path, mime_type, created_at)
                    VALUES (1, 'legacy.jpg', '/archive/legacy.jpg', 'image/jpeg',
                            '2024-03-15 08:00:00')
                    """);
        }
    }

    private static void executeStatements(Statement statement, String schema) throws SQLException {
        StringBuilder sql = new StringBuilder();
        boolean trigger = false;
        for (String line : schema.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            if (sql.isEmpty()) {
                trigger = trimmed.toUpperCase().startsWith("CREATE TRIGGER");
            }
            sql.append(line).append('\n');
            if (trigger ? "END;".equalsIgnoreCase(trimmed) : trimmed.endsWith(";")) {
                statement.execute(sql.toString());
                sql.setLength(0);
                trigger = false;
            }
        }
        require(sql.isEmpty(), "The v2 fixture schema must be complete");
    }

    private static void requireUuidKeys(Statement statement, String table) throws Exception {
        try (ResultSet result = statement.executeQuery(
                "SELECT stable_key FROM " + table + " ORDER BY id")) {
            int count = 0;
            Set<String> keys = new java.util.HashSet<>();
            while (result.next()) {
                String key = result.getString(1);
                require(key != null && !key.isBlank(), table + " keys must not be blank");
                UUID.fromString(key);
                require(keys.add(key), table + " keys must be unique");
                count++;
            }
            require(count > 0, table + " must retain its representative row");
        }
    }

    private static void requireNotNullColumn(Statement statement, String table, String column)
            throws Exception {
        try (ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equals(result.getString("name"))) {
                    require(result.getInt("notnull") == 1,
                            table + "." + column + " must be NOT NULL");
                    return;
                }
            }
        }
        throw new AssertionError("Missing required column " + table + "." + column);
    }

    private static long singleLong(Statement statement, String query) throws Exception {
        try (ResultSet result = statement.executeQuery(query)) {
            require(result.next(), "Expected one result for " + query);
            if ("ok".equalsIgnoreCase(result.getString(1))) {
                return 0;
            }
            return result.getLong(1);
        }
    }

    private static String singleString(Statement statement, String query) throws Exception {
        try (ResultSet result = statement.executeQuery(query)) {
            require(result.next(), "Expected one result for " + query);
            return result.getString(1);
        }
    }

    private static void requireColumns(Statement statement, String table, Set<String> expected)
            throws Exception {
        Set<String> found = new java.util.HashSet<>();
        try (ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                found.add(result.getString("name"));
            }
        }
        require(found.containsAll(expected), "Missing v3 columns in " + table);
    }

    private static void requireNoColumn(Statement statement, String table, String column)
            throws Exception {
        try (ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                require(!column.equals(result.getString("name")),
                        table + " must not associate legacy media to an event");
            }
        }
    }

    private static void requireForeignKey(
            Statement statement,
            String table,
            String from,
            String destination
    ) throws Exception {
        try (ResultSet result = statement.executeQuery("PRAGMA foreign_key_list(" + table + ")")) {
            while (result.next()) {
                if (from.equals(result.getString("from"))
                        && destination.equals(result.getString("table"))) {
                    return;
                }
            }
        }
        throw new AssertionError("Missing foreign key " + table + "." + from + " -> " + destination);
    }

    private static void requireObjects(Statement statement, String type, Set<String> names)
            throws Exception {
        for (String name : names) {
            require(objectExists(statement, type, name), "Missing " + type + ": " + name);
        }
    }

    private static boolean objectExists(Statement statement, String type, String name) throws Exception {
        try (ResultSet result = statement.executeQuery("""
                SELECT 1 FROM sqlite_master WHERE type = '%s' AND name = '%s'
                """.formatted(type, name))) {
            return result.next();
        }
    }

    private static void requireSqlFailure(ThrowingRunnable action, String message) throws Exception {
        try {
            action.run();
        } catch (SQLException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final String V2_SCHEMA = """
            PRAGMA foreign_keys = ON;
            CREATE TABLE species (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                code TEXT UNIQUE,
                scientific_name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                common_name TEXT,
                active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT
            );
            CREATE TABLE bird (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ring_number TEXT NOT NULL COLLATE NOCASE UNIQUE,
                species_id INTEGER NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT,
                FOREIGN KEY (species_id) REFERENCES species(id) ON UPDATE CASCADE ON DELETE RESTRICT
            );
            CREATE TABLE place (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
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
            );
            CREATE UNIQUE INDEX idx_place_single_default ON place(is_default) WHERE is_default = 1;
            CREATE TABLE bird_event (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
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
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT,
                FOREIGN KEY (bird_id) REFERENCES bird(id) ON UPDATE CASCADE ON DELETE RESTRICT,
                FOREIGN KEY (place_id) REFERENCES place(id) ON UPDATE CASCADE ON DELETE SET NULL
            );
            CREATE TABLE event_photo (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_id INTEGER NOT NULL,
                file_name TEXT NOT NULL,
                file_path TEXT NOT NULL,
                mime_type TEXT,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (event_id) REFERENCES bird_event(id) ON UPDATE CASCADE ON DELETE CASCADE,
                UNIQUE (event_id, file_path)
            );
            CREATE INDEX idx_bird_species ON bird(species_id);
            CREATE INDEX idx_event_bird ON bird_event(bird_id);
            CREATE INDEX idx_event_date ON bird_event(event_date);
            CREATE INDEX idx_event_type ON bird_event(event_type);
            CREATE INDEX idx_event_place ON bird_event(place_id);
            CREATE INDEX idx_event_bird_date ON bird_event(bird_id, event_date DESC, event_time DESC);
            CREATE INDEX idx_place_favorite ON place(is_favorite DESC, name);
            CREATE INDEX idx_photo_event ON event_photo(event_id);
            CREATE TRIGGER trg_species_updated_at AFTER UPDATE ON species FOR EACH ROW
            WHEN NEW.updated_at IS OLD.updated_at BEGIN
                UPDATE species SET updated_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
            END;
            CREATE TRIGGER trg_bird_updated_at AFTER UPDATE ON bird FOR EACH ROW
            WHEN NEW.updated_at IS OLD.updated_at BEGIN
                UPDATE bird SET updated_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
            END;
            CREATE TRIGGER trg_place_updated_at AFTER UPDATE ON place FOR EACH ROW
            WHEN NEW.updated_at IS OLD.updated_at BEGIN
                UPDATE place SET updated_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
            END;
            CREATE TRIGGER trg_bird_event_updated_at AFTER UPDATE ON bird_event FOR EACH ROW
            WHEN NEW.updated_at IS OLD.updated_at BEGIN
                UPDATE bird_event SET updated_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
            END;
            """;

    public static void main(String[] args) throws Exception {
        freshDatabaseIsV5AndCurrentEntitiesGenerateStableKeys();
        exactPragmaZeroV2IsDetectedAsLogicalSchemaTwo();
        populatedV2DatabaseMigratesWithoutLosingValues();
        unknownOrNewerDatabasesAreRejectedWithoutRebuild();
        initializingV5AgainKeepsStableKeys();
        failedV2MigrationRollsBackEverySchemaChange();
        System.out.println("SchemaMigrationTest: PASS");
    }
}
