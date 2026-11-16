package com.adelylria.ringlog.database.entity;

import java.util.UUID;

import com.adelylria.ringlog.model.EnvironmentCategory;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = EnvironmentOptionEntity.TABLE)
public class EnvironmentOptionEntity {
    public static final String TABLE = "environment_option";
    public static final String CATEGORY = "category";
    public static final String SORT_ORDER = "sort_order";

    @DatabaseField(columnName = "id", generatedId = true)
    private long id;
    @DatabaseField(columnName = "stable_key", canBeNull = false, unique = true)
    private String stableKey = UUID.randomUUID().toString();
    @DatabaseField(columnName = CATEGORY, canBeNull = false)
    private EnvironmentCategory category;
    @DatabaseField(columnName = "name", canBeNull = false)
    private String name;
    @DatabaseField(columnName = "active", canBeNull = false)
    private boolean active = true;
    @DatabaseField(columnName = SORT_ORDER, canBeNull = false)
    private int sortOrder;

    public EnvironmentOptionEntity() { }
    public EnvironmentOptionEntity(EnvironmentCategory category, String name, int sortOrder) {
        this.category = category;
        this.name = name;
        this.sortOrder = sortOrder;
    }

    public long id() { return id; }
    public String getStableKey() { return stableKey; }
    public EnvironmentCategory getCategory() { return category; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
