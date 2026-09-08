package com.adelylria.ringlog.importexport.nativeformat;

import java.nio.file.Path;

public record ExportResult(Path file, String exportId, int binaryCount) {
}
