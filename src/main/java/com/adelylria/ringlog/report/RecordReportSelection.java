package com.adelylria.ringlog.report;

import java.util.List;

public record RecordReportSelection(
        List<Long> eventIds,
        List<String> activeFilters
) {
    public RecordReportSelection {
        eventIds = List.copyOf(eventIds);
        activeFilters = List.copyOf(activeFilters);
    }
}
