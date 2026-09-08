package com.adelylria.ringlog.portable;

public record PortableCopyProgress(
        PortableCopyStage stage,
        String message,
        long completedBytes,
        long totalBytes
) {
}
