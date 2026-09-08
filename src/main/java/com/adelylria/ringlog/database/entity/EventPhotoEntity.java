package com.adelylria.ringlog.database.entity;

import java.util.UUID;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = EventPhotoEntity.TABLE)
public class EventPhotoEntity {

    public static final String TABLE = "event_photo";
    public static final String ID = "id";
    public static final String STABLE_KEY = "stable_key";
    public static final String EVENT_ID = "event_id";
    public static final String FILE_NAME = "file_name";
    public static final String FILE_PATH = "file_path";
    public static final String MIME_TYPE = "mime_type";

    @DatabaseField(columnName = ID, generatedId = true)
    private long id;

    @DatabaseField(columnName = STABLE_KEY, canBeNull = false, unique = true)
    private String stableKey = UUID.randomUUID().toString();

    @DatabaseField(columnName = EVENT_ID, canBeNull = false)
    private long eventId;

    @DatabaseField(columnName = FILE_NAME, canBeNull = false)
    private String fileName;

    @DatabaseField(columnName = FILE_PATH, canBeNull = false)
    private String filePath;

    @DatabaseField(columnName = MIME_TYPE)
    private String mimeType;

    @DatabaseField(columnName = "source_name")
    private String sourceName;

    @DatabaseField(columnName = "source_reference")
    private String sourceReference;

    @DatabaseField(columnName = "content_sha256")
    private String contentSha256;

    @DatabaseField(columnName = "content_size")
    private Long contentSize;

    public EventPhotoEntity() {
    }

    public String filePath() {
        return filePath;
    }

    public String getStableKey() {
        return stableKey;
    }

    public void setStableKey(String stableKey) {
        this.stableKey = stableKey;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getEventId() {
        return eventId;
    }

    public void setEventId(long eventId) {
        this.eventId = eventId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public String getSourceReference() {
        return sourceReference;
    }

    public void setSourceReference(String sourceReference) {
        this.sourceReference = sourceReference;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public void setContentSha256(String contentSha256) {
        this.contentSha256 = contentSha256;
    }

    public Long getContentSize() {
        return contentSize;
    }

    public void setContentSize(Long contentSize) {
        this.contentSize = contentSize;
    }
}
