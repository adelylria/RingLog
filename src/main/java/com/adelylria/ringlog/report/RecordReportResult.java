package com.adelylria.ringlog.report;

import java.nio.file.Path;

public record RecordReportResult(
        Path path,
        int recordCount,
        RecordReportFormat format
) {
}
