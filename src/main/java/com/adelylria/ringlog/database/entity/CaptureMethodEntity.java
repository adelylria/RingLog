package com.adelylria.ringlog.database.entity;

import java.util.UUID;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = CaptureMethodEntity.TABLE)
public class CaptureMethodEntity {

    public static final String TABLE = "capture_method";
    public static final String ID = "id";
    public static final String STABLE_KEY = "stable_key";
    public static final String NAME = "name";
    public static final String ACTIVE = "active";

    @DatabaseField(columnName = ID, generatedId = true)
    private long id;

    @DatabaseField(columnName = STABLE_KEY, canBeNull = false, unique = true)
    private String stableKey = UUID.randomUUID().toString();

    @DatabaseField(columnName = NAME, canBeNull = false)
    private String name;

    @DatabaseField(columnName = ACTIVE, canBeNull = false)
    private boolean active = true;

    public CaptureMethodEntity() {
    }

    public CaptureMethodEntity(String name) {
        this.name = name;
    }

    public long id() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
