package com.adelylria.ringlog.database.entity;

import java.util.UUID;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = EventSourceAliasEntity.TABLE)
public class EventSourceAliasEntity {
    public static final String TABLE = "event_source_alias";

    @DatabaseField(generatedId = true)
    private long id;
    @DatabaseField(columnName = "stable_key", canBeNull = false, unique = true)
    private String stableKey = UUID.randomUUID().toString();
    @DatabaseField(columnName = "event_id", canBeNull = false)
    private long eventId;
    @DatabaseField(columnName = "source_name", canBeNull = false)
    private String sourceName;
    @DatabaseField(columnName = "source_reference", canBeNull = false)
    private String sourceReference;

    public EventSourceAliasEntity() {
    }

    public void setStableKey(String stableKey) {
        this.stableKey = stableKey;
    }

    public void setEventId(long eventId) {
        this.eventId = eventId;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public void setSourceReference(String sourceReference) {
        this.sourceReference = sourceReference;
    }
}
