package com.adelylria.ringlog.importexport;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validated workbook identity and metadata, with no domain rows parsed yet. */
public record WorkbookProfile(
        ImportFormat format,
        Map<String, String> metadata,
        Set<String> sheets
) {

    public WorkbookProfile {
        format = Objects.requireNonNull(format, "format");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        sheets = Set.copyOf(Objects.requireNonNull(sheets, "sheets"));
    }
}
