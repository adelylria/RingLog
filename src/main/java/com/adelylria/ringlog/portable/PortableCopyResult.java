package com.adelylria.ringlog.portable;

import java.nio.file.Path;

public record PortableCopyResult(
        Path portableRoot,
        long copiedBytes,
        long immutableFileCount,
        long eventCount,
        long photoCount
) {
}
