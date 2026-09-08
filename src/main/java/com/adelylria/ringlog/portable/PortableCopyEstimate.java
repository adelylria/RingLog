package com.adelylria.ringlog.portable;

import java.nio.file.Path;

public record PortableCopyEstimate(
        Path target,
        long estimatedCopyBytes,
        long safetyMarginBytes,
        long requiredFreeBytes,
        long usableSpaceBytes,
        boolean replacingExistingCopy
) {
    public boolean hasEnoughSpace() {
        return usableSpaceBytes >= requiredFreeBytes;
    }
}
