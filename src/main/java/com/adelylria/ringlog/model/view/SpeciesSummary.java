package com.adelylria.ringlog.model.view;

public record SpeciesSummary(
        long id,
        String name,
        String code,
        String scientificName,
        String commonName,
        long eventCount
) {
}
