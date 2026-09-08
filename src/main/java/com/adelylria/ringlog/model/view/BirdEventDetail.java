package com.adelylria.ringlog.model.view;

import java.util.List;

import com.adelylria.ringlog.model.EventType;

public record BirdEventDetail(
        long id,
        long birdId,
        String ringNumber,
        String species,
        EventType eventType,
        String eventDate,
        String eventTime,
        String place,
        String locality,
        String locationText,
        String captureType,
        String sexCode,
        String ageEuringCode,
        Integer fatScore,
        Integer muscleScore,
        String ringerInitials,
        String status,
        String reproductiveStatus,
        String moultIntensity,
        String moultExtension,
        String birdCondition,
        String returnStatus,
        Double wing,
        Double p3,
        Double torso,
        Double weight,
        String clouds,
        String rain,
        String thermalSensation,
        String wind,
        Double latitude,
        Double longitude,
        String observations,
        boolean dead,
        String reviewStatus,
        String reviewNote,
        List<String> photoPaths,
        long speciesId,
        Long placeId
) {
    public BirdEventDetail {
        photoPaths = List.copyOf(photoPaths);
    }

    public BirdEventDetail(
            long id,
            long birdId,
            String ringNumber,
            String species,
            EventType eventType,
            String eventDate,
            String eventTime,
            String place,
            String locality,
            String locationText,
            String captureType,
            String sexCode,
            String ageEuringCode,
            Integer fatScore,
            Integer muscleScore,
            String ringerInitials,
            String status,
            String reproductiveStatus,
            String moultIntensity,
            String moultExtension,
            String birdCondition,
            String returnStatus,
            Double wing,
            Double p3,
            Double torso,
            Double weight,
            String clouds,
            String rain,
            String thermalSensation,
            String wind,
            Double latitude,
            Double longitude,
            String observations,
            boolean dead,
            String reviewStatus,
            String reviewNote,
            List<String> photoPaths
    ) {
        this(
                id, birdId, ringNumber, species, eventType, eventDate, eventTime,
                place, locality, locationText, captureType, sexCode, ageEuringCode,
                fatScore, muscleScore, ringerInitials, status, reproductiveStatus,
                moultIntensity, moultExtension, birdCondition, returnStatus,
                wing, p3, torso, weight, clouds, rain, thermalSensation, wind,
                latitude, longitude, observations, dead, reviewStatus, reviewNote,
                photoPaths, 0L, null
        );
    }

    public BirdEventDetail(
            long id,
            long birdId,
            String ringNumber,
            String species,
            EventType eventType,
            String eventDate,
            String eventTime,
            String place,
            String locality,
            String locationText,
            String captureType,
            String sexCode,
            String ageEuringCode,
            Integer fatScore,
            Integer muscleScore,
            String ringerInitials,
            String status,
            String reproductiveStatus,
            String moultIntensity,
            String moultExtension,
            String birdCondition,
            String returnStatus,
            Double wing,
            Double p3,
            Double torso,
            Double weight,
            String clouds,
            String rain,
            String thermalSensation,
            String wind,
            Double latitude,
            Double longitude,
            String observations,
            boolean dead,
            List<String> photoPaths
    ) {
        this(
                id, birdId, ringNumber, species, eventType, eventDate, eventTime,
                place, locality, locationText, captureType, sexCode, ageEuringCode,
                fatScore, muscleScore, ringerInitials, status, reproductiveStatus,
                moultIntensity, moultExtension, birdCondition, returnStatus,
                wing, p3, torso, weight, clouds, rain, thermalSensation, wind,
                latitude, longitude, observations, dead, "OK", null, photoPaths,
                0L, null
        );
    }
}
