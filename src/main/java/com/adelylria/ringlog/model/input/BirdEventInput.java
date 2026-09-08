package com.adelylria.ringlog.model.input;

import com.adelylria.ringlog.model.EventType;

public record BirdEventInput(
        String ringNumber,
        Long speciesId,
        EventType eventType,
        String eventDate,
        String eventTime,
        Long placeId,
        String locationText,
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
        String observations,
        String clouds,
        String rain,
        String thermalSensation,
        String wind,
        String captureType,
        boolean dead
) {
}
