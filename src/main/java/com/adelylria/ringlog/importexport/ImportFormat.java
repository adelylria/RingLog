package com.adelylria.ringlog.importexport;

import java.util.Set;

/** Interchange profiles that this RingLog version deliberately supports. */
public enum ImportFormat {

    LEGACY_V5(Set.of(
            "metadata", "species", "birds", "places", "events", "photos",
            "unassigned_photos", "source_records", "migration_conflicts",
            "migration_audit", "migration_warnings"
    )),
    RINGLOG_BACKUP_V3(Set.of(
            "metadata", "species", "birds", "places", "events", "photos",
            "migration_warnings", "photo_content", "text_content"
    )),
    RINGLOG_EXPORT_V1(Set.of(
            "metadata", "species", "birds", "places", "events", "photos",
            "import_batches", "import_metadata", "event_source_aliases",
            "source_records", "unassigned_photos", "migration_audit",
            "migration_conflicts", "migration_warnings", "text_content"
    ));

    private final Set<String> requiredSheets;

    ImportFormat(Set<String> requiredSheets) {
        this.requiredSheets = Set.copyOf(requiredSheets);
    }

    public Set<String> requiredSheets() {
        return requiredSheets;
    }
}
