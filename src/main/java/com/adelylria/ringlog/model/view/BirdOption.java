package com.adelylria.ringlog.model.view;

public record BirdOption(
        long id,
        String ringNumber,
        long speciesId,
        String species
) {
    @Override
    public String toString() {
        return ringNumber + " · " + species;
    }
}
