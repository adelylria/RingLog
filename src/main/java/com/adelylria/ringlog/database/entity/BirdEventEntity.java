package com.adelylria.ringlog.database.entity;

import java.util.UUID;

import com.adelylria.ringlog.model.input.BirdEventInput;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = BirdEventEntity.TABLE)
public class BirdEventEntity {

    public static final String TABLE = "bird_event";
    public static final String ID = "id";
    public static final String STABLE_KEY = "stable_key";
    public static final String MIGRATION_KEY = "migration_key";
    public static final String BIRD_ID = "bird_id";
    public static final String SOURCE_NAME = "source_name";
    public static final String SOURCE_REFERENCE = "source_reference";

    @DatabaseField(columnName = ID, generatedId = true)
    private long id;

    @DatabaseField(columnName = STABLE_KEY, canBeNull = false, unique = true)
    private String stableKey = UUID.randomUUID().toString();

    @DatabaseField(columnName = MIGRATION_KEY)
    private String migrationKey;

    @DatabaseField(columnName = BIRD_ID, canBeNull = false)
    private long birdId;

    @DatabaseField(columnName = "event_type", canBeNull = false)
    private String eventType;

    @DatabaseField(columnName = "event_date", canBeNull = false)
    private String eventDate;

    @DatabaseField(columnName = "event_time")
    private String eventTime;

    @DatabaseField(columnName = "place_id")
    private Long placeId;

    @DatabaseField(columnName = "location_text")
    private String locationText;

    @DatabaseField(columnName = "sex_code")
    private String sexCode;

    @DatabaseField(columnName = "age_euring_code")
    private String ageEuringCode;

    @DatabaseField(columnName = "fat_score")
    private Integer fatScore;

    @DatabaseField(columnName = "muscle_score")
    private Integer muscleScore;

    @DatabaseField(columnName = "ringer_initials")
    private String ringerInitials;

    @DatabaseField(columnName = "status")
    private String status;

    @DatabaseField(columnName = "reproductive_status")
    private String reproductiveStatus;

    @DatabaseField(columnName = "moult_intensity")
    private String moultIntensity;

    @DatabaseField(columnName = "moult_extension")
    private String moultExtension;

    @DatabaseField(columnName = "bird_condition")
    private String birdCondition;

    @DatabaseField(columnName = "return_status")
    private String returnStatus;

    @DatabaseField(columnName = "wing")
    private Double wing;

    @DatabaseField(columnName = "p3")
    private Double p3;

    @DatabaseField(columnName = "torso")
    private Double torso;

    @DatabaseField(columnName = "weight")
    private Double weight;

    @DatabaseField(columnName = "observations")
    private String observations;

    @DatabaseField(columnName = "clouds")
    private String clouds;

    @DatabaseField(columnName = "rain")
    private String rain;

    @DatabaseField(columnName = "thermal_sensation")
    private String thermalSensation;

    @DatabaseField(columnName = "wind")
    private String wind;

    @DatabaseField(columnName = "capture_type")
    private String captureType;

    @DatabaseField(columnName = "is_dead", canBeNull = false)
    private boolean dead;

    @DatabaseField(columnName = SOURCE_NAME)
    private String sourceName;

    @DatabaseField(columnName = SOURCE_REFERENCE)
    private String sourceReference;

    @DatabaseField(columnName = "review_status", canBeNull = false)
    private String reviewStatus = "OK";

    @DatabaseField(columnName = "review_note")
    private String reviewNote;

    public BirdEventEntity() {
    }

    public BirdEventEntity(long birdId, BirdEventInput input) {
        this.birdId = birdId;
        apply(input);
    }

    public void apply(BirdEventInput input) {
        eventType = input.eventType().databaseValue();
        eventDate = input.eventDate().trim();
        eventTime = blankToNull(input.eventTime());
        placeId = input.placeId();
        locationText = blankToNull(input.locationText());
        sexCode = blankToNull(input.sexCode());
        ageEuringCode = blankToNull(input.ageEuringCode());
        fatScore = input.fatScore();
        muscleScore = input.muscleScore();
        ringerInitials = blankToNull(input.ringerInitials());
        status = blankToNull(input.status());
        reproductiveStatus = blankToNull(input.reproductiveStatus());
        moultIntensity = blankToNull(input.moultIntensity());
        moultExtension = blankToNull(input.moultExtension());
        birdCondition = blankToNull(input.birdCondition());
        returnStatus = blankToNull(input.returnStatus());
        wing = input.wing();
        p3 = input.p3();
        torso = input.torso();
        weight = input.weight();
        observations = blankToNull(input.observations());
        clouds = blankToNull(input.clouds());
        rain = blankToNull(input.rain());
        thermalSensation = blankToNull(input.thermalSensation());
        wind = blankToNull(input.wind());
        captureType = blankToNull(input.captureType());
        dead = input.dead();
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

    public String getMigrationKey() {
        return migrationKey;
    }

    public void setMigrationKey(String migrationKey) {
        this.migrationKey = migrationKey;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public long getBirdId() {
        return birdId;
    }

    public void setBirdId(long birdId) {
        this.birdId = birdId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getEventDate() {
        return eventDate;
    }

    public void setEventDate(String eventDate) {
        this.eventDate = eventDate;
    }

    public String getEventTime() {
        return eventTime;
    }

    public void setEventTime(String eventTime) {
        this.eventTime = eventTime;
    }

    public Long getPlaceId() {
        return placeId;
    }

    public void setPlaceId(Long placeId) {
        this.placeId = placeId;
    }

    public String getLocationText() {
        return locationText;
    }

    public void setLocationText(String locationText) {
        this.locationText = locationText;
    }

    public String getSexCode() {
        return sexCode;
    }

    public void setSexCode(String sexCode) {
        this.sexCode = sexCode;
    }

    public String getAgeEuringCode() {
        return ageEuringCode;
    }

    public void setAgeEuringCode(String ageEuringCode) {
        this.ageEuringCode = ageEuringCode;
    }

    public Integer getFatScore() {
        return fatScore;
    }

    public void setFatScore(Integer fatScore) {
        this.fatScore = fatScore;
    }

    public Integer getMuscleScore() {
        return muscleScore;
    }

    public void setMuscleScore(Integer muscleScore) {
        this.muscleScore = muscleScore;
    }

    public String getRingerInitials() {
        return ringerInitials;
    }

    public void setRingerInitials(String ringerInitials) {
        this.ringerInitials = ringerInitials;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReproductiveStatus() {
        return reproductiveStatus;
    }

    public void setReproductiveStatus(String reproductiveStatus) {
        this.reproductiveStatus = reproductiveStatus;
    }

    public String getMoultIntensity() {
        return moultIntensity;
    }

    public void setMoultIntensity(String moultIntensity) {
        this.moultIntensity = moultIntensity;
    }

    public String getMoultExtension() {
        return moultExtension;
    }

    public void setMoultExtension(String moultExtension) {
        this.moultExtension = moultExtension;
    }

    public String getBirdCondition() {
        return birdCondition;
    }

    public void setBirdCondition(String birdCondition) {
        this.birdCondition = birdCondition;
    }

    public String getReturnStatus() {
        return returnStatus;
    }

    public void setReturnStatus(String returnStatus) {
        this.returnStatus = returnStatus;
    }

    public Double getWing() {
        return wing;
    }

    public void setWing(Double wing) {
        this.wing = wing;
    }

    public Double getP3() {
        return p3;
    }

    public void setP3(Double p3) {
        this.p3 = p3;
    }

    public Double getTorso() {
        return torso;
    }

    public void setTorso(Double torso) {
        this.torso = torso;
    }

    public Double getWeight() {
        return weight;
    }

    public void setWeight(Double weight) {
        this.weight = weight;
    }

    public String getObservations() {
        return observations;
    }

    public void setObservations(String observations) {
        this.observations = observations;
    }

    public String getClouds() {
        return clouds;
    }

    public void setClouds(String clouds) {
        this.clouds = clouds;
    }

    public String getRain() {
        return rain;
    }

    public void setRain(String rain) {
        this.rain = rain;
    }

    public String getThermalSensation() {
        return thermalSensation;
    }

    public void setThermalSensation(String thermalSensation) {
        this.thermalSensation = thermalSensation;
    }

    public String getWind() {
        return wind;
    }

    public void setWind(String wind) {
        this.wind = wind;
    }

    public String getCaptureType() {
        return captureType;
    }

    public void setCaptureType(String captureType) {
        this.captureType = captureType;
    }

    public boolean isDead() {
        return dead;
    }

    public void setDead(boolean dead) {
        this.dead = dead;
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

    public String getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(String reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public void setReviewNote(String reviewNote) {
        this.reviewNote = reviewNote;
    }
}
