package com.adelylria.ringlog.model.view;

import com.adelylria.ringlog.model.EnvironmentCategory;

public record EnvironmentOption(
        long id,
        String stableKey,
        EnvironmentCategory category,
        String name,
        boolean active,
        int sortOrder
) {
    @Override public String toString() { return name; }
}
