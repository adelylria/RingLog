package com.adelylria.ringlog.model.input;

public record PlaceInput(
        String name,
        String locality,
        String autonomousCommunity,
        String country,
        Double latitude,
        Double longitude,
        String notes,
        boolean favorite,
        boolean isDefault
) {
    public PlaceInput(
            String name,
            String locality,
            Double latitude,
            Double longitude,
            String notes,
            boolean favorite,
            boolean isDefault
    ) {
        this(name, locality, null, null, latitude, longitude, notes, favorite, isDefault);
    }
}
