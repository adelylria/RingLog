package com.adelylria.ringlog.storage;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/** Non-sensitive technical information shown when RingLog cannot select a DB safely. */
public record DatabaseCandidate(
        Path path,
        long size,
        FileTime modifiedAt,
        String sha256
) {
}
