package com.adelylria.ringlog.model.view;

public record PlaceSummary(
        long id,
        String name,
        String locality,
        String autonomousCommunity,
        String country,
        Double latitude,
        Double longitude,
        String notes,
        boolean favorite,
        boolean isDefault,
        long eventCount
) {
    public PlaceSummary(
            long id,
            String name,
            String locality,
            Double latitude,
            Double longitude,
            String notes,
            boolean favorite,
            boolean isDefault,
            long eventCount
    ) {
        this(id, name, locality, null, null, latitude, longitude, notes,
                favorite, isDefault, eventCount);
    }

    @Override
    public String toString() {
        return locality == null || locality.isBlank()
                ? name
                : name + " · " + locality;
    }
}
