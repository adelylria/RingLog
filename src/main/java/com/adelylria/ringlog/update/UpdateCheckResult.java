package com.adelylria.ringlog.update;

import java.util.Objects;

public record UpdateCheckResult(Status status, UpdateManifest manifest, UpdateAsset asset) {

    public enum Status {
        UPDATE_AVAILABLE,
        UP_TO_DATE,
        UNSUPPORTED_ARCHITECTURE
    }

    public UpdateCheckResult {
        status = Objects.requireNonNull(status, "status");
        if (status == Status.UPDATE_AVAILABLE && (manifest == null || asset == null)) {
            throw new IllegalArgumentException("An available update needs its manifest and asset");
        }
    }

    public static UpdateCheckResult available(UpdateManifest manifest, UpdateAsset asset) {
        return new UpdateCheckResult(Status.UPDATE_AVAILABLE, manifest, asset);
    }

    public static UpdateCheckResult upToDate(UpdateManifest manifest) {
        return new UpdateCheckResult(Status.UP_TO_DATE, manifest, null);
    }

    public static UpdateCheckResult unsupported(UpdateManifest manifest) {
        return new UpdateCheckResult(Status.UNSUPPORTED_ARCHITECTURE, manifest, null);
    }
}
