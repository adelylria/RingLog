package com.adelylria.ringlog.portable;

public record PortableDatasetSummary(
        long species,
        long birds,
        long places,
        long events,
        long eventPhotos,
        long unassignedPhotos
) {
    public long photoCount() {
        return eventPhotos + unassignedPhotos;
    }
}
