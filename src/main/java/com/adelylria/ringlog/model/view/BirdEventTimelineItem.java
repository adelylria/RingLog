package com.adelylria.ringlog.model.view;

import java.util.List;

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
        String reviewStatus,
        List<EventType> historyEventTypes
) {

    public BirdEventTimelineItem {
        historyEventTypes = historyEventTypes == null || historyEventTypes.isEmpty()
                ? List.of(eventType)
                : List.copyOf(historyEventTypes);
    }

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
            String photoPath,
            String reviewStatus
    ) {
        this(
                id, birdId, ringNumber, eventType, eventDate, eventTime,
                species, place, observations, birdCondition, photoPath,
                reviewStatus, List.of(eventType)
        );
    }

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
                species, place, observations, birdCondition, photoPath, "OK",
                List.of(eventType)
        );
    }
}
