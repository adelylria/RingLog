package com.adelylria.ringlog.model.view;

public record ReportSummary(
        DashboardStats stats,
        SpeciesSummary mostFrequentSpecies,
        PlaceSummary mostFrequentPlace
) {
}
