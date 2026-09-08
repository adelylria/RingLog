package com.adelylria.ringlog.model.view;

import java.util.List;
import java.util.Objects;

import com.adelylria.ringlog.model.EventType;

/** Immutable, complete projection used by federation reports. */
public record BirdEventReportRow(
        BirdEventDetail detail,
        String speciesCode,
        String speciesScientificName,
        String speciesCommonName,
        String sourceName,
        String sourceReference,
        int photoCount
) {

    public BirdEventReportRow {
        detail = Objects.requireNonNull(detail, "detail");
        if (photoCount < 0) {
            throw new IllegalArgumentException("photoCount cannot be negative");
        }
    }

    public static BirdEventReportRow fromDetail(BirdEventDetail detail) {
        return new BirdEventReportRow(
                detail,
                null,
                detail.species(),
                null,
                null,
                null,
                detail.photoPaths().size()
        );
    }

    public long id() {
        return detail.id();
    }

    public long birdId() {
        return detail.birdId();
    }

    public String ringNumber() {
        return detail.ringNumber();
    }

    public String species() {
        return detail.species();
    }

    public EventType eventType() {
        return detail.eventType();
    }

    public String eventDate() {
        return detail.eventDate();
    }

    public String eventTime() {
        return detail.eventTime();
    }

    public String place() {
        return detail.place();
    }

    public String locality() {
        return detail.locality();
    }

    public String locationText() {
        return detail.locationText();
    }

    public String captureType() {
        return detail.captureType();
    }

    public String sexCode() {
        return detail.sexCode();
    }

    public String ageEuringCode() {
        return detail.ageEuringCode();
    }

    public Integer fatScore() {
        return detail.fatScore();
    }

    public Integer muscleScore() {
        return detail.muscleScore();
    }

    public String ringerInitials() {
        return detail.ringerInitials();
    }

    public String status() {
        return detail.status();
    }

    public String reproductiveStatus() {
        return detail.reproductiveStatus();
    }

    public String moultIntensity() {
        return detail.moultIntensity();
    }

    public String moultExtension() {
        return detail.moultExtension();
    }

    public String birdCondition() {
        return detail.birdCondition();
    }

    public String returnStatus() {
        return detail.returnStatus();
    }

    public Double wing() {
        return detail.wing();
    }

    public Double p3() {
        return detail.p3();
    }

    public Double torso() {
        return detail.torso();
    }

    public Double weight() {
        return detail.weight();
    }

    public String clouds() {
        return detail.clouds();
    }

    public String rain() {
        return detail.rain();
    }

    public String thermalSensation() {
        return detail.thermalSensation();
    }

    public String wind() {
        return detail.wind();
    }

    public Double latitude() {
        return detail.latitude();
    }

    public Double longitude() {
        return detail.longitude();
    }

    public String observations() {
        return detail.observations();
    }

    public boolean dead() {
        return detail.dead();
    }

    public List<String> photoPaths() {
        return detail.photoPaths();
    }
}
