package com.adelylria.ringlog.database.entity;

import java.util.UUID;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = PlaceEntity.TABLE)
public class PlaceEntity {

    public static final String TABLE = "place";
    public static final String ID = "id";
    public static final String STABLE_KEY = "stable_key";
    public static final String NAME = "name";
    public static final String LOCALITY = "locality";
    public static final String LATITUDE = "latitude";
    public static final String LONGITUDE = "longitude";
    public static final String NOTES = "notes";
    public static final String FAVORITE = "is_favorite";
    public static final String DEFAULT = "is_default";
    public static final String ACTIVE = "active";

    @DatabaseField(columnName = ID, generatedId = true)
    private long id;

    @DatabaseField(columnName = STABLE_KEY, canBeNull = false, unique = true)
    private String stableKey = UUID.randomUUID().toString();

    @DatabaseField(columnName = NAME, canBeNull = false)
    private String name;

    @DatabaseField(columnName = LOCALITY)
    private String locality;

    @DatabaseField(columnName = LATITUDE)
    private Double latitude;

    @DatabaseField(columnName = LONGITUDE)
    private Double longitude;

    @DatabaseField(columnName = NOTES)
    private String notes;

    @DatabaseField(columnName = FAVORITE, canBeNull = false)
    private boolean favorite;

    @DatabaseField(columnName = DEFAULT, canBeNull = false)
    private boolean defaultPlace;

    @DatabaseField(columnName = ACTIVE, canBeNull = false)
    private boolean active = true;

    public PlaceEntity() {
    }

    public PlaceEntity(
            String name,
            String locality,
            Double latitude,
            Double longitude,
            String notes,
            boolean favorite,
            boolean defaultPlace
    ) {
        this.name = name;
        this.locality = locality;
        this.latitude = latitude;
        this.longitude = longitude;
        this.notes = notes;
        this.favorite = favorite;
        this.defaultPlace = defaultPlace;
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

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLocality() {
        return locality;
    }

    public void setLocality(String locality) {
        this.locality = locality;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public boolean isFavorite() {
        return favorite;
    }

    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }

    public boolean isDefaultPlace() {
        return defaultPlace;
    }

    public void setDefaultPlace(boolean defaultPlace) {
        this.defaultPlace = defaultPlace;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
