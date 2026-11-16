package com.adelylria.ringlog.importexport.service;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.adelylria.ringlog.importexport.compatibility.RingLogV3BackupModel.EnvironmentOptionRow;
import com.adelylria.ringlog.model.EnvironmentDefaults;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.GenericRawResults;

/** Restores catalogues without replacing local customizations or historical event text. */
public final class EnvironmentCatalogRestore {
    private EnvironmentCatalogRestore() { }

    public static void restore(Dao<?, ?> anchor, List<EnvironmentOptionRow> incoming) throws SQLException {
        if (incoming.isEmpty()) return;
        Map<String, EnvironmentOptionRow> existing = new HashMap<>();
        try (GenericRawResults<String[]> results = anchor.queryRaw("""
                SELECT stable_key, category, name, active, sort_order FROM environment_option
                """)) {
            for (String[] row : results.getResults()) {
                existing.put(row[0], new EnvironmentOptionRow(row[0], row[1], row[2],
                        "1".equals(row[3]), Integer.parseInt(row[4])));
            }
        } catch (Exception exception) {
            throw new SQLException("No se pudo leer el catálogo de entorno.", exception);
        }
        Map<String, EnvironmentOptionRow> incomingByKey = incoming.stream().collect(
                Collectors.toMap(EnvironmentOptionRow::stableKey, Function.identity()));
        // A fresh target has these placeholders. Replace only the untouched defaults;
        // archived, renamed or reordered local options are never silently overwritten.
        for (var seed : EnvironmentDefaults.options()) {
            EnvironmentOptionRow pristine = new EnvironmentOptionRow(seed.stableKey(),
                    seed.category().code(), seed.name(), seed.active(), seed.sortOrder());
            if (incomingByKey.containsKey(seed.stableKey())
                    && pristine.equals(existing.get(seed.stableKey()))) {
                anchor.executeRaw("DELETE FROM environment_option WHERE stable_key = ?", seed.stableKey());
                existing.remove(seed.stableKey());
            }
        }
        for (EnvironmentOptionRow row : incoming) {
            EnvironmentOptionRow local = existing.get(row.stableKey());
            if (local != null) {
                if (!Objects.equals(local, row)) {
                    throw new SQLException("La opción de entorno «" + local.name()
                            + "» ya existe con otros ajustes. Se han conservado los datos locales.");
                }
                continue;
            }
            anchor.executeRaw("""
                    INSERT INTO environment_option(stable_key, category, name, active, sort_order)
                    VALUES (?, ?, ?, ?, ?)
                    """, row.stableKey(), row.category(), row.name(), row.active() ? "1" : "0",
                    Integer.toString(row.sortOrder()));
            existing.put(row.stableKey(), row);
        }
    }
}
