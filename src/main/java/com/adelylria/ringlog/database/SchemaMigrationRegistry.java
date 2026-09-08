package com.adelylria.ringlog.database;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Resolves a deterministic chain of one-version schema migration steps. */
public final class SchemaMigrationRegistry {

    private final Map<Integer, SchemaMigration> migrationsBySource;

    public SchemaMigrationRegistry(List<? extends SchemaMigration> migrations) {
        Map<Integer, SchemaMigration> registered = new HashMap<>();
        for (SchemaMigration migration : migrations) {
            if (migration.targetSchema() != migration.fromSchema() + 1) {
                throw new IllegalArgumentException(
                        "Las migraciones deben avanzar exactamente una versión: "
                                + migration.fromSchema() + " -> " + migration.targetSchema()
                );
            }
            if (registered.putIfAbsent(migration.fromSchema(), migration) != null) {
                throw new IllegalArgumentException(
                        "Ya existe una migración desde schema v" + migration.fromSchema()
                );
            }
        }
        migrationsBySource = Map.copyOf(registered);
    }

    public SchemaUpgradePlan plan(int fromSchema, int targetSchema) {
        if (fromSchema < 0 || targetSchema < fromSchema) {
            throw new IllegalArgumentException(
                    "Rango de migración no válido: v" + fromSchema + " -> v" + targetSchema
            );
        }

        java.util.ArrayList<SchemaMigration> steps = new java.util.ArrayList<>();
        for (int version = fromSchema; version < targetSchema; version++) {
            SchemaMigration migration = migrationsBySource.get(version);
            if (migration == null) {
                throw new IllegalStateException(
                        "No existe una migración registrada desde schema v" + version
                );
            }
            steps.add(migration);
        }
        return new SchemaUpgradePlan(fromSchema, targetSchema, steps);
    }
}
