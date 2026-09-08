package com.adelylria.ringlog.database.entity;

import java.util.UUID;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = ImportBatchEntity.TABLE)
public class ImportBatchEntity {
    public static final String TABLE = "import_batch";

    @DatabaseField(generatedId = true)
    private long id;
    @DatabaseField(columnName = "stable_key", canBeNull = false, unique = true)
    private String stableKey = UUID.randomUUID().toString();
    @DatabaseField(columnName = "source_format", canBeNull = false)
    private String sourceFormat;
    @DatabaseField(columnName = "format_version", canBeNull = false)
    private String formatVersion;
    @DatabaseField(columnName = "source_name", canBeNull = false)
    private String sourceName;
    @DatabaseField(columnName = "source_reference")
    private String sourceReference;
    @DatabaseField(columnName = "fingerprint")
    private String fingerprint;
    @DatabaseField(columnName = "export_id")
    private String exportId;
    @DatabaseField(columnName = "import_mode", canBeNull = false)
    private String importMode;
    @DatabaseField(columnName = "imported_at", canBeNull = false)
    private String importedAt;

    public ImportBatchEntity() {
    }

    public long id() {
        return id;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setStableKey(String stableKey) {
        this.stableKey = stableKey;
    }

    public void setSourceFormat(String sourceFormat) {
        this.sourceFormat = sourceFormat;
    }

    public void setFormatVersion(String formatVersion) {
        this.formatVersion = formatVersion;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public void setSourceReference(String sourceReference) {
        this.sourceReference = sourceReference;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public void setExportId(String exportId) {
        this.exportId = exportId;
    }

    public void setImportMode(String importMode) {
        this.importMode = importMode;
    }

    public void setImportedAt(String importedAt) {
        this.importedAt = importedAt;
    }
}
