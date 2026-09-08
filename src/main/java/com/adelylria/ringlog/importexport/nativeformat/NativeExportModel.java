package com.adelylria.ringlog.importexport.nativeformat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fully validated native workbook plus resolved package media. */
public record NativeExportModel(
        Map<String, String> metadata,
        Map<String, List<NativeRow>> sheets,
        Map<String, MediaFile> media
) {

    public NativeExportModel {
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        Map<String, List<NativeRow>> copiedSheets = new LinkedHashMap<>();
        for (Map.Entry<String, List<NativeRow>> entry
                : Objects.requireNonNull(sheets, "sheets").entrySet()) {
            copiedSheets.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        sheets = Collections.unmodifiableMap(copiedSheets);
        media = Map.copyOf(Objects.requireNonNull(media, "media"));
    }

    public List<NativeRow> rows(String sheet) {
        return sheets.getOrDefault(sheet, List.of());
    }

    public List<NativeRow> conflicts() {
        return rows("migration_conflicts");
    }

    public String exportId() {
        return metadata.get("export_id");
    }

    public record NativeRow(Map<String, String> values) {
        public NativeRow {
            Map<String, String> copy = new LinkedHashMap<>();
            copy.putAll(Objects.requireNonNull(values, "values"));
            values = Collections.unmodifiableMap(copy);
        }

        public String value(String column) {
            if (!values.containsKey(column)) {
                throw new IllegalArgumentException("Columna nativa desconocida: " + column);
            }
            return values.get(column);
        }
    }

    public record MediaFile(
            String packagePath,
            Path resolvedPath,
            String contentSha256,
            long contentSize
    ) {
        public MediaFile {
            Objects.requireNonNull(packagePath, "packagePath");
            Objects.requireNonNull(resolvedPath, "resolvedPath");
            Objects.requireNonNull(contentSha256, "contentSha256");
            if (contentSize < 0) {
                throw new IllegalArgumentException("El tamaño del binario no puede ser negativo.");
            }
        }
    }

    static Map<String, List<NativeRow>> emptySheets() {
        Map<String, List<NativeRow>> result = new LinkedHashMap<>();
        for (String sheet : NativeSchema.SHEETS.keySet()) {
            result.put(sheet, new ArrayList<>());
        }
        return result;
    }
}
