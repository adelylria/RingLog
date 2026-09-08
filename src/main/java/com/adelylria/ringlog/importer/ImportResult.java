package com.adelylria.ringlog.importer;

public record ImportResult(
        int speciesCreated,
        int speciesReused,
        int birdsCreated,
        int birdsReused,
        int placesCreated,
        int placesReused,
        int eventsCreated,
        int eventsSkipped,
        int photosCreated,
        int photosSkipped
) {
}
