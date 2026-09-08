package com.adelylria.ringlog.database;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Objects;

import com.adelylria.ringlog.storage.ManagedMediaMigrationService;
import com.adelylria.ringlog.storage.MigrationResult;

/** Staged migration from physical legacy paths to managed media references. */
public final class V3ToV4StorageMigration implements StagedStorageMigration {

    private final ManagedMediaMigrationService mediaMigration;

    public V3ToV4StorageMigration() {
        this(new ManagedMediaMigrationService());
    }

    V3ToV4StorageMigration(ManagedMediaMigrationService mediaMigration) {
        this.mediaMigration = Objects.requireNonNull(mediaMigration, "mediaMigration");
    }

    @Override
    public int fromSchema() {
        return 3;
    }

    @Override
    public int targetSchema() {
        return 4;
    }

    @Override
    public MigrationResult migrate(StagedStorageMigrationContext context)
            throws SQLException, IOException {
        return mediaMigration.migrateManaged(
                context.appPaths(),
                context.progressListener()
        );
    }
}
