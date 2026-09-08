package com.adelylria.ringlog.model.view;

import com.adelylria.ringlog.model.EventType;

public record BirdEventListItem(
        long id,
        String ringNumber,
        String eventDate,
        EventType eventType,
        String species,
        String place
) {
}
