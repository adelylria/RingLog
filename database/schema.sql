PRAGMA foreign_keys = ON;

-- RingLog schema v3. Stable identities are supplied by Java, never SQLite.
CREATE TABLE species (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    stable_key TEXT NOT NULL CHECK (length(trim(stable_key)) > 0),
    code TEXT UNIQUE,
    scientific_name TEXT NOT NULL COLLATE NOCASE UNIQUE,
    common_name TEXT,
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT
);

CREATE TABLE bird (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    stable_key TEXT NOT NULL CHECK (length(trim(stable_key)) > 0),
    ring_number TEXT NOT NULL COLLATE NOCASE UNIQUE,
    species_id INTEGER NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT,
    FOREIGN KEY (species_id) REFERENCES species(id) ON UPDATE CASCADE ON DELETE RESTRICT
);

CREATE TABLE place (
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
);

CREATE TABLE bird_event (
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
    FOREIGN KEY (bird_id) REFERENCES bird(id) ON UPDATE CASCADE ON DELETE RESTRICT,
    FOREIGN KEY (place_id) REFERENCES place(id) ON UPDATE CASCADE ON DELETE SET NULL
);

CREATE TABLE event_photo (
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
    FOREIGN KEY (event_id) REFERENCES bird_event(id) ON UPDATE CASCADE ON DELETE CASCADE,
    UNIQUE (event_id, file_path)
);

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
    imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (length(trim(stable_key)) > 0)
);

CREATE TABLE import_metadata (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    import_batch_id INTEGER NOT NULL,
    metadata_key TEXT NOT NULL,
    metadata_value TEXT,
    FOREIGN KEY (import_batch_id) REFERENCES import_batch(id) ON DELETE CASCADE,
    UNIQUE (import_batch_id, metadata_key)
);

CREATE TABLE event_source_alias (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    stable_key TEXT NOT NULL CHECK (length(trim(stable_key)) > 0),
    event_id INTEGER NOT NULL,
    source_name TEXT NOT NULL,
    source_reference TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (event_id) REFERENCES bird_event(id) ON DELETE RESTRICT,
    UNIQUE (event_id, source_reference)
);

CREATE TABLE legacy_source_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    stable_key TEXT NOT NULL CHECK (length(trim(stable_key)) > 0),
    import_batch_id INTEGER NOT NULL,
    source_name TEXT,
    source_reference TEXT NOT NULL,
    source_section TEXT,
    ring_number TEXT,
    raw_payload TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (import_batch_id) REFERENCES import_batch(id) ON DELETE RESTRICT,
    UNIQUE (import_batch_id, source_reference)
);

CREATE TABLE legacy_unassigned_photo (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    stable_key TEXT NOT NULL CHECK (length(trim(stable_key)) > 0),
    import_batch_id INTEGER NOT NULL,
    source_name TEXT,
    source_reference TEXT NOT NULL,
    file_name TEXT NOT NULL,
    file_path TEXT,
    mime_type TEXT,
    content_sha256 TEXT,
    content_size INTEGER CHECK (content_size IS NULL OR content_size >= 0),
    width INTEGER,
    height INTEGER,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (import_batch_id) REFERENCES import_batch(id) ON DELETE CASCADE,
    UNIQUE (import_batch_id, source_reference)
);

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
);

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
);

CREATE TABLE migration_warning (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    stable_key TEXT NOT NULL CHECK (length(trim(stable_key)) > 0),
    import_batch_id INTEGER NOT NULL,
    severity TEXT,
    source TEXT,
    reference TEXT,
    message TEXT NOT NULL,
    FOREIGN KEY (import_batch_id) REFERENCES import_batch(id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX idx_species_stable_key ON species(stable_key);
CREATE UNIQUE INDEX idx_bird_stable_key ON bird(stable_key);
CREATE UNIQUE INDEX idx_place_stable_key ON place(stable_key);
CREATE UNIQUE INDEX idx_event_stable_key ON bird_event(stable_key);
CREATE UNIQUE INDEX idx_photo_stable_key ON event_photo(stable_key);
CREATE UNIQUE INDEX idx_event_migration_key ON bird_event(migration_key) WHERE migration_key IS NOT NULL;
CREATE UNIQUE INDEX idx_photo_source_reference ON event_photo(source_name, source_reference)
WHERE source_reference IS NOT NULL;
CREATE INDEX idx_photo_content_sha256 ON event_photo(content_sha256) WHERE content_sha256 IS NOT NULL;
CREATE UNIQUE INDEX idx_import_batch_stable_key ON import_batch(stable_key);
CREATE UNIQUE INDEX idx_import_batch_fingerprint ON import_batch(fingerprint) WHERE fingerprint IS NOT NULL;
CREATE UNIQUE INDEX idx_import_batch_export_id ON import_batch(export_id) WHERE export_id IS NOT NULL;
CREATE UNIQUE INDEX idx_event_source_alias_stable_key ON event_source_alias(stable_key);
CREATE UNIQUE INDEX idx_legacy_source_record_stable_key ON legacy_source_record(stable_key);
CREATE UNIQUE INDEX idx_legacy_unassigned_photo_stable_key ON legacy_unassigned_photo(stable_key);
CREATE UNIQUE INDEX idx_migration_audit_stable_key ON migration_audit(stable_key);
CREATE UNIQUE INDEX idx_migration_conflict_stable_key ON migration_conflict(stable_key);
CREATE UNIQUE INDEX idx_migration_conflict_key ON migration_conflict(conflict_key);
CREATE UNIQUE INDEX idx_migration_warning_stable_key ON migration_warning(stable_key);
CREATE UNIQUE INDEX idx_place_single_default ON place(is_default) WHERE is_default = 1;
CREATE INDEX idx_bird_species ON bird(species_id);
CREATE INDEX idx_event_bird ON bird_event(bird_id);
CREATE INDEX idx_event_date ON bird_event(event_date);
CREATE INDEX idx_event_type ON bird_event(event_type);
CREATE INDEX idx_event_place ON bird_event(place_id);
CREATE INDEX idx_event_bird_date ON bird_event(bird_id, event_date DESC, event_time DESC);
CREATE INDEX idx_place_favorite ON place(is_favorite DESC, name);
CREATE INDEX idx_photo_event ON event_photo(event_id);
CREATE INDEX idx_import_metadata_batch ON import_metadata(import_batch_id);
CREATE INDEX idx_event_source_alias_event ON event_source_alias(event_id);
CREATE INDEX idx_event_source_alias_reference ON event_source_alias(source_name, source_reference);
CREATE INDEX idx_legacy_source_record_batch ON legacy_source_record(import_batch_id);
CREATE INDEX idx_legacy_source_record_reference ON legacy_source_record(source_name, source_reference);
CREATE INDEX idx_legacy_unassigned_photo_batch ON legacy_unassigned_photo(import_batch_id);
CREATE INDEX idx_legacy_unassigned_photo_hash ON legacy_unassigned_photo(content_sha256);
CREATE INDEX idx_migration_audit_batch ON migration_audit(import_batch_id);
CREATE INDEX idx_migration_audit_reference ON migration_audit(source_name, source_reference);
CREATE INDEX idx_migration_conflict_open ON migration_conflict(resolved_at) WHERE resolved_at IS NULL;
CREATE INDEX idx_migration_conflict_event ON migration_conflict(event_id);
CREATE INDEX idx_migration_warning_batch ON migration_warning(import_batch_id);
CREATE INDEX idx_migration_warning_reference ON migration_warning(source, reference);

CREATE TRIGGER trg_species_updated_at
AFTER UPDATE ON species FOR EACH ROW WHEN NEW.updated_at IS OLD.updated_at
BEGIN
    UPDATE species SET updated_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
END;

CREATE TRIGGER trg_bird_updated_at
AFTER UPDATE ON bird FOR EACH ROW WHEN NEW.updated_at IS OLD.updated_at
BEGIN
    UPDATE bird SET updated_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
END;

CREATE TRIGGER trg_place_updated_at
AFTER UPDATE ON place FOR EACH ROW WHEN NEW.updated_at IS OLD.updated_at
BEGIN
    UPDATE place SET updated_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
END;

CREATE TRIGGER trg_bird_event_updated_at
AFTER UPDATE ON bird_event FOR EACH ROW WHEN NEW.updated_at IS OLD.updated_at
BEGIN
    UPDATE bird_event SET updated_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
END;

PRAGMA user_version = 4;
