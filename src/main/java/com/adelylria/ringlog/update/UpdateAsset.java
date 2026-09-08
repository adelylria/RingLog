package com.adelylria.ringlog.update;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record UpdateAsset(URI url, String sha256, long size) {

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    public UpdateAsset {
        url = Objects.requireNonNull(url, "url");
        sha256 = Objects.requireNonNull(sha256, "sha256").toLowerCase(Locale.ROOT);
        if (!SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("Invalid SHA-256");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Update size must be positive");
        }
    }
}
