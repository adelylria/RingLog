package com.adelylria.ringlog.model.view;

import com.adelylria.ringlog.model.EventType;

public record BirdEventTimelineItem(
        long id,
        long birdId,
        String ringNumber,
        EventType eventType,
        String eventDate,
        String eventTime,
        String species,
        String place,
        String observations,
        String birdCondition,
        String photoPath,
        String reviewStatus
) {

    public BirdEventTimelineItem(
            long id,
            long birdId,
            String ringNumber,
            EventType eventType,
            String eventDate,
            String eventTime,
            String species,
            String place,
            String observations,
            String birdCondition,
            String photoPath
    ) {
        this(
                id, birdId, ringNumber, eventType, eventDate, eventTime,
                species, place, observations, birdCondition, photoPath, "OK"
        );
    }
}
