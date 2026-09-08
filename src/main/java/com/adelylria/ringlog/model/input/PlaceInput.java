package com.adelylria.ringlog.model.input;

public record PlaceInput(
        String name,
        String locality,
        Double latitude,
        Double longitude,
        String notes,
        boolean favorite,
        boolean isDefault
) {
}
