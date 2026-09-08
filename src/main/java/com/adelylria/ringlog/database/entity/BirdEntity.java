package com.adelylria.ringlog.database.entity;

import java.util.UUID;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = BirdEntity.TABLE)
public class BirdEntity {

    public static final String TABLE = "bird";
    public static final String ID = "id";
    public static final String STABLE_KEY = "stable_key";
    public static final String RING_NUMBER = "ring_number";
    public static final String SPECIES_ID = "species_id";

    @DatabaseField(columnName = ID, generatedId = true)
    private long id;

    @DatabaseField(columnName = STABLE_KEY, canBeNull = false, unique = true)
    private String stableKey = UUID.randomUUID().toString();

    @DatabaseField(columnName = RING_NUMBER, canBeNull = false)
    private String ringNumber;

    @DatabaseField(columnName = SPECIES_ID, canBeNull = false)
    private long speciesId;

    public BirdEntity() {
    }

    public BirdEntity(String ringNumber, long speciesId) {
        this.ringNumber = ringNumber;
        this.speciesId = speciesId;
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

    public String getRingNumber() {
        return ringNumber;
    }

    public long getSpeciesId() {
        return speciesId;
    }

    public void setSpeciesId(long speciesId) {
        this.speciesId = speciesId;
    }

    public void setRingNumber(String ringNumber) {
        this.ringNumber = ringNumber;
    }
}
