package com.adelylria.ringlog.portable;

import java.util.Objects;

import com.adelylria.ringlog.storage.PortableAppPaths;

/** Read-only startup validation; it never initializes or upgrades a portable database. */
public final class PortableStorageBootstrap {

    private final PortableCopyValidator validator;

    public PortableStorageBootstrap() {
        this(new PortableCopyValidator());
    }

    PortableStorageBootstrap(PortableCopyValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public PortableDatasetSummary prepare(PortableAppPaths paths)
            throws PortableCopyException {
        return validator.validateStartup(Objects.requireNonNull(paths, "paths"));
    }
}
