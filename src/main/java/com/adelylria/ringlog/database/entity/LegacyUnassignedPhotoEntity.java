package com.adelylria.ringlog.database.entity;

import java.util.UUID;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = LegacyUnassignedPhotoEntity.TABLE)
public class LegacyUnassignedPhotoEntity {
    public static final String TABLE = "legacy_unassigned_photo";

    @DatabaseField(generatedId = true)
    private long id;
    @DatabaseField(columnName = "stable_key", canBeNull = false, unique = true)
    private String stableKey = UUID.randomUUID().toString();
    @DatabaseField(columnName = "import_batch_id", canBeNull = false)
    private long importBatchId;
    @DatabaseField(columnName = "file_name", canBeNull = false)
    private String fileName;
    @DatabaseField(columnName = "file_path")
    private String filePath;
    @DatabaseField(columnName = "mime_type")
    private String mimeType;
    @DatabaseField(columnName = "content_sha256")
    private String contentSha256;
    @DatabaseField(columnName = "content_size")
    private Long contentSize;
    @DatabaseField(columnName = "width")
    private Integer width;
    @DatabaseField(columnName = "height")
    private Integer height;
    @DatabaseField(columnName = "source_reference", canBeNull = false)
    private String sourceReference;
    @DatabaseField(columnName = "created_at", canBeNull = false)
    private String createdAt;

    public LegacyUnassignedPhotoEntity() {
    }

    public void setStableKey(String stableKey) {
        this.stableKey = stableKey;
    }

    public void setImportBatchId(long importBatchId) {
        this.importBatchId = importBatchId;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public void setContentSha256(String contentSha256) {
        this.contentSha256 = contentSha256;
    }

    public void setContentSize(Long contentSize) {
        this.contentSize = contentSize;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public void setSourceReference(String sourceReference) {
        this.sourceReference = sourceReference;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
