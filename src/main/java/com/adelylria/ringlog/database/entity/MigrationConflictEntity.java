package com.adelylria.ringlog.database.entity;

import java.util.UUID;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = MigrationConflictEntity.TABLE)
public class MigrationConflictEntity {
    public static final String TABLE = "migration_conflict";

    @DatabaseField(generatedId = true)
    private long id;
    @DatabaseField(columnName = "stable_key", canBeNull = false, unique = true)
    private String stableKey = UUID.randomUUID().toString();
    @DatabaseField(columnName = "conflict_key", canBeNull = false, unique = true)
    private String conflictKey;
    @DatabaseField(columnName = "import_batch_id")
    private Long importBatchId;
    @DatabaseField(columnName = "event_id")
    private Long eventId;
    @DatabaseField(columnName = "ring_number")
    private String ringNumber;
    @DatabaseField(columnName = "conflict_type", canBeNull = false)
    private String conflictType;
    @DatabaseField(columnName = "event_type")
    private String eventType;
    @DatabaseField(columnName = "field_name")
    private String fieldName;
    @DatabaseField(columnName = "canonical_source_reference")
    private String canonicalSourceReference;
    @DatabaseField(columnName = "canonical_value")
    private String canonicalValue;
    @DatabaseField(columnName = "alternative_source_reference")
    private String alternativeSourceReference;
    @DatabaseField(columnName = "alternative_value")
    private String alternativeValue;
    @DatabaseField(columnName = "canonical_snapshot")
    private String canonicalSnapshot;
    @DatabaseField(columnName = "alternative_snapshot")
    private String alternativeSnapshot;
    @DatabaseField(columnName = "original_note")
    private String originalNote;
    @DatabaseField(columnName = "status")
    private String status;
    @DatabaseField(columnName = "resolution_type")
    private String resolutionType;
    @DatabaseField(columnName = "resolution_value")
    private String resolutionValue;
    @DatabaseField(columnName = "resolved_at")
    private String resolvedAt;
    @DatabaseField(columnName = "resolution_notes")
    private String resolutionNotes;

    public MigrationConflictEntity() {
    }

    public long id() {
        return id;
    }

    public Long getEventId() {
        return eventId;
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getCanonicalValue() {
        return canonicalValue;
    }

    public String getAlternativeValue() {
        return alternativeValue;
    }

    public String getStatus() {
        return status;
    }

    public void setStableKey(String stableKey) {
        this.stableKey = stableKey;
    }

    public void setConflictKey(String conflictKey) {
        this.conflictKey = conflictKey;
    }

    public void setImportBatchId(Long importBatchId) {
        this.importBatchId = importBatchId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public void setRingNumber(String ringNumber) {
        this.ringNumber = ringNumber;
    }

    public void setConflictType(String conflictType) {
        this.conflictType = conflictType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public void setCanonicalSourceReference(String value) {
        canonicalSourceReference = value;
    }

    public void setCanonicalValue(String canonicalValue) {
        this.canonicalValue = canonicalValue;
    }

    public void setAlternativeSourceReference(String value) {
        alternativeSourceReference = value;
    }

    public void setAlternativeValue(String alternativeValue) {
        this.alternativeValue = alternativeValue;
    }

    public void setCanonicalSnapshot(String canonicalSnapshot) {
        this.canonicalSnapshot = canonicalSnapshot;
    }

    public void setAlternativeSnapshot(String alternativeSnapshot) {
        this.alternativeSnapshot = alternativeSnapshot;
    }

    public void setOriginalNote(String originalNote) {
        this.originalNote = originalNote;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setResolutionType(String resolutionType) {
        this.resolutionType = resolutionType;
    }

    public void setResolutionValue(String resolutionValue) {
        this.resolutionValue = resolutionValue;
    }

    public void setResolvedAt(String resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public void setResolutionNotes(String resolutionNotes) {
        this.resolutionNotes = resolutionNotes;
    }
}
