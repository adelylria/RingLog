package com.adelylria.ringlog.database;

import java.util.List;

/** Outcome of opening or upgrading one managed RingLog database. */
public record DatabaseUpgradeResult(
        int fromSchema,
        int targetSchema,
        List<SchemaMigration> steps,
        SchemaBackup backup
) {

    public DatabaseUpgradeResult {
        steps = List.copyOf(steps);
    }
}
