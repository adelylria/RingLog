package com.adelylria.ringlog.model.view;

public record BirdLookup(
        long id,
        String ringNumber,
        long speciesId,
        String species,
        long eventCount
) {
}
