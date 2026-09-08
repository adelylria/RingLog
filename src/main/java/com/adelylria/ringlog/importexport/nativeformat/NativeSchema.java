package com.adelylria.ringlog.importexport.nativeformat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

/** Ordered, versioned workbook contract for RingLog Export v1. */
final class NativeSchema {

    static final Map<String, List<String>> SHEETS;

    static {
        Map<String, List<String>> sheets = new LinkedHashMap<>();
        sheets.put("species", List.of(
                "stable_key", "code", "scientific_name", "common_name", "active",
                "created_at", "updated_at"
        ));
        sheets.put("birds", List.of(
                "stable_key", "ring_number", "species_stable_key", "created_at", "updated_at"
        ));
        sheets.put("places", List.of(
                "stable_key", "name", "locality", "latitude", "longitude", "notes",
                "is_favorite", "is_default", "active", "created_at", "updated_at"
        ));
        sheets.put("events", List.of(
                "stable_key", "migration_key", "bird_stable_key", "event_type", "event_date",
                "event_time", "place_stable_key", "location_text", "sex_code",
                "age_euring_code", "fat_score", "muscle_score", "ringer_initials", "status",
                "reproductive_status", "moult_intensity", "moult_extension", "bird_condition",
                "return_status", "wing", "p3", "torso", "weight", "observations", "clouds",
                "rain", "thermal_sensation", "wind", "capture_type", "is_dead",
                "source_name", "source_reference", "review_status", "review_note",
                "created_at", "updated_at"
        ));
        sheets.put("photos", List.of(
                "stable_key", "event_stable_key", "file_name", "original_file_path",
                "media_path", "mime_type", "source_name", "source_reference",
                "content_sha256", "content_size", "package_sha256", "package_size",
                "created_at"
        ));
        sheets.put("import_batches", List.of(
                "stable_key", "source_format", "format_version", "source_name",
                "source_reference", "fingerprint", "export_id", "import_mode", "imported_at"
        ));
        sheets.put("import_metadata", List.of(
                "import_batch_stable_key", "metadata_key", "metadata_value"
        ));
        sheets.put("event_source_aliases", List.of(
                "stable_key", "event_stable_key", "source_name", "source_reference", "created_at"
        ));
        sheets.put("source_records", List.of(
                "stable_key", "import_batch_stable_key", "source_name", "source_section",
                "source_reference", "ring_number", "raw_payload", "created_at"
        ));
        sheets.put("unassigned_photos", List.of(
                "stable_key", "import_batch_stable_key", "source_name", "source_reference",
                "file_name", "original_file_path", "media_path", "mime_type",
                "content_sha256", "content_size", "package_sha256", "package_size",
                "width", "height", "created_at"
        ));
        sheets.put("migration_audit", List.of(
                "stable_key", "import_batch_stable_key", "source_name", "source_section",
                "source_reference", "source_field", "original_value", "original_display",
                "destination", "normalized_value", "status", "note"
        ));
        sheets.put("migration_conflicts", List.of(
                "stable_key", "conflict_key", "import_batch_stable_key", "event_stable_key",
                "ring_number", "conflict_type", "event_type", "field_name",
                "canonical_value", "alternative_value", "canonical_source_reference",
                "alternative_source_reference", "canonical_snapshot", "alternative_snapshot",
                "original_note", "status", "resolution_type", "resolution_value",
                "resolved_at", "resolution_notes", "created_at"
        ));
        sheets.put("migration_warnings", List.of(
                "stable_key", "import_batch_stable_key", "severity", "source", "reference",
                "message"
        ));
        SHEETS = Collections.unmodifiableMap(new LinkedHashMap<>(sheets));
    }

    private NativeSchema() {
    }
}
