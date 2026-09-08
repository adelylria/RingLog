package com.adelylria.ringlog.database.entity;

import java.util.UUID;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = LegacySourceRecordEntity.TABLE)
public class LegacySourceRecordEntity {
    public static final String TABLE = "legacy_source_record";

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
    @DatabaseField(columnName = "source_reference", canBeNull = false)
    private String sourceReference;
    @DatabaseField(columnName = "ring_number")
    private String ringNumber;
    @DatabaseField(columnName = "raw_payload", canBeNull = false)
    private String rawPayload;

    public LegacySourceRecordEntity() {
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

    public void setRingNumber(String ringNumber) {
        this.ringNumber = ringNumber;
    }

    public void setRawPayload(String rawPayload) {
        this.rawPayload = rawPayload;
    }
}
