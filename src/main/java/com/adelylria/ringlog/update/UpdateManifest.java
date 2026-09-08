package com.adelylria.ringlog.update;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record UpdateManifest(
        SemanticVersion version,
        Map<UpdateArchitecture, UpdateAsset> assets
) {
    public UpdateManifest {
        version = Objects.requireNonNull(version, "version");
        assets = Map.copyOf(Objects.requireNonNull(assets, "assets"));
        if (assets.isEmpty()) {
            throw new IllegalArgumentException("The update manifest needs at least one asset");
        }
    }

    public Optional<UpdateAsset> assetFor(UpdateArchitecture architecture) {
        return Optional.ofNullable(assets.get(architecture));
    }
}
