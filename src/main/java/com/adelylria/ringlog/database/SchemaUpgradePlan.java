package com.adelylria.ringlog.database;

import java.util.List;

/** Immutable, contiguous upgrade path between two schema versions. */
public record SchemaUpgradePlan(
        int fromSchema,
        int targetSchema,
        List<SchemaMigration> steps
) {

    public SchemaUpgradePlan {
        steps = List.copyOf(steps);
    }
}
