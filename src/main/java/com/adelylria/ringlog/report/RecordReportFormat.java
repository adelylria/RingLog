package com.adelylria.ringlog.report;

import java.nio.file.Path;
import java.util.Locale;

public enum RecordReportFormat {
    EXCEL("xlsx", "Excel (.xlsx)", 5_000),
    PDF("pdf", "PDF (.pdf)", 1_000);

    private final String extension;
    private final String displayName;
    private final int maximumRecords;

    RecordReportFormat(String extension, String displayName, int maximumRecords) {
        this.extension = extension;
        this.displayName = displayName;
        this.maximumRecords = maximumRecords;
    }

    public String extension() {
        return extension;
    }

    public int maximumRecords() {
        return maximumRecords;
    }

    public Path pathFor(Path destination) {
        String name = destination.getFileName().toString();
        String suffix = "." + extension.toLowerCase(Locale.ROOT);
        if (name.toLowerCase(Locale.ROOT).endsWith(suffix)) {
            return destination;
        }
        return destination.resolveSibling(name + suffix);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
