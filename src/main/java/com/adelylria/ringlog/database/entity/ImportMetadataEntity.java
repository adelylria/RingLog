package com.adelylria.ringlog.database.entity;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = ImportMetadataEntity.TABLE)
public class ImportMetadataEntity {
    public static final String TABLE = "import_metadata";

    @DatabaseField(generatedId = true)
    private long id;
    @DatabaseField(columnName = "import_batch_id", canBeNull = false)
    private long importBatchId;
    @DatabaseField(columnName = "metadata_key", canBeNull = false)
    private String metadataKey;
    @DatabaseField(columnName = "metadata_value")
    private String metadataValue;

    public ImportMetadataEntity() {
    }

    public void setImportBatchId(long importBatchId) {
        this.importBatchId = importBatchId;
    }

    public void setMetadataKey(String metadataKey) {
        this.metadataKey = metadataKey;
    }

    public void setMetadataValue(String metadataValue) {
        this.metadataValue = metadataValue;
    }
}
