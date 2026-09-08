package com.adelylria.ringlog.database.entity;

import java.util.UUID;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = MigrationAuditEntity.TABLE)
public class MigrationAuditEntity {
    public static final String TABLE = "migration_audit";

    @DatabaseField(generatedId = true)
    private long id;
    @DatabaseField(columnName = "stable_key", canBeNull = false, unique = true)
    private String stableKey = UUID.randomUUID().toString();
    @DatabaseField(columnName = "import_batch_id", canBeNull = false)
    private long importBatchId;
    @DatabaseField(columnName = "source_name")
    private String sourceName;
    @DatabaseField(columnName = "source_section")
    private String sourceSection;
    @DatabaseField(columnName = "source_reference")
    private String sourceReference;
    @DatabaseField(columnName = "source_field")
    private String sourceField;
    @DatabaseField(columnName = "original_value")
    private String originalValue;
    @DatabaseField(columnName = "original_display")
    private String originalDisplay;
    @DatabaseField(columnName = "destination")
    private String destination;
    @DatabaseField(columnName = "normalized_value")
    private String normalizedValue;
    @DatabaseField(columnName = "status")
    private String status;
    @DatabaseField(columnName = "note")
    private String note;

    public MigrationAuditEntity() {
    }

    public void setStableKey(String stableKey) {
        this.stableKey = stableKey;
    }

    public void setImportBatchId(long importBatchId) {
        this.importBatchId = importBatchId;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public void setSourceSection(String sourceSection) {
        this.sourceSection = sourceSection;
    }

    public void setSourceReference(String sourceReference) {
        this.sourceReference = sourceReference;
    }

    public void setSourceField(String sourceField) {
        this.sourceField = sourceField;
    }

    public void setOriginalValue(String originalValue) {
        this.originalValue = originalValue;
    }

    public void setOriginalDisplay(String originalDisplay) {
        this.originalDisplay = originalDisplay;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public void setNormalizedValue(String normalizedValue) {
        this.normalizedValue = normalizedValue;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
