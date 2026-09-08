package com.adelylria.ringlog.database.entity;

import java.util.UUID;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = SpeciesEntity.TABLE)
public class SpeciesEntity {

    public static final String TABLE = "species";
    public static final String ID = "id";
    public static final String STABLE_KEY = "stable_key";
    public static final String CODE = "code";
    public static final String SCIENTIFIC_NAME = "scientific_name";
    public static final String COMMON_NAME = "common_name";
    public static final String ACTIVE = "active";

    @DatabaseField(columnName = ID, generatedId = true)
    private long id;

    @DatabaseField(columnName = STABLE_KEY, canBeNull = false, unique = true)
    private String stableKey = UUID.randomUUID().toString();

    @DatabaseField(columnName = CODE)
    private String code;

    @DatabaseField(columnName = SCIENTIFIC_NAME, canBeNull = false)
    private String scientificName;

    @DatabaseField(columnName = COMMON_NAME)
    private String commonName;

    @DatabaseField(columnName = ACTIVE, canBeNull = false)
    private boolean active = true;

    public SpeciesEntity() {
    }

    public SpeciesEntity(String code, String scientificName, String commonName) {
        this.code = code;
        this.scientificName = scientificName;
        this.commonName = commonName;
    }

    public long id() {
        return id;
    }

    public String getStableKey() {
        return stableKey;
    }

    public void setStableKey(String stableKey) {
        this.stableKey = stableKey;
    }

    public String displayName() {
        return commonName == null || commonName.isBlank()
                ? scientificName
                : commonName;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getScientificName() {
        return scientificName;
    }

    public void setScientificName(String scientificName) {
        this.scientificName = scientificName;
    }

    public String getCommonName() {
        return commonName;
    }

    public void setCommonName(String commonName) {
        this.commonName = commonName;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
