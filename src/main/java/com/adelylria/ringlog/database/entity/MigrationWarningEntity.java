package com.adelylria.ringlog.database.entity;

import java.util.UUID;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = MigrationWarningEntity.TABLE)
public class MigrationWarningEntity {
    public static final String TABLE = "migration_warning";

    @DatabaseField(generatedId = true)
    private long id;
    @DatabaseField(columnName = "stable_key", canBeNull = false, unique = true)
    private String stableKey = UUID.randomUUID().toString();
    @DatabaseField(columnName = "import_batch_id")
    private Long importBatchId;
    @DatabaseField(columnName = "severity")
    private String severity;
    @DatabaseField(columnName = "source")
    private String source;
    @DatabaseField(columnName = "reference")
    private String reference;
    @DatabaseField(columnName = "message", canBeNull = false)
    private String message;

    public MigrationWarningEntity() {
    }

    public void setStableKey(String stableKey) {
        this.stableKey = stableKey;
    }

    public void setImportBatchId(Long importBatchId) {
        this.importBatchId = importBatchId;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
