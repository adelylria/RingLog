package com.adelylria.ringlog.model.view;

public record DashboardStats(
        long eventCount,
        long speciesCount,
        long placeCount,
        String latestEventDate
) {
}
