package com.adelylria.ringlog.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;

import com.adelylria.ringlog.database.DatabaseUpgradeService;

/** Entry point for moving a working-directory database into managed storage. */
public final class LegacyDataLocationMigrator {

    private final ManagedMediaMigrationService mediaMigration;

    public LegacyDataLocationMigrator() {
        this(new ManagedMediaMigrationService());
    }

    LegacyDataLocationMigrator(
            SQLiteBackupService backupService,
            DatabaseUpgradeService upgradeService
    ) {
        this(new ManagedMediaMigrationService(backupService, upgradeService));
    }

    LegacyDataLocationMigrator(ManagedMediaMigrationService mediaMigration) {
        this.mediaMigration = Objects.requireNonNull(mediaMigration, "mediaMigration");
    }

    public MigrationResult migrate(
            Path sourceDatabase,
            AppPaths destinationPaths,
            MigrationProgressListener progressListener
    ) throws SQLException, IOException {
        return mediaMigration.migrate(sourceDatabase, destinationPaths, progressListener);
    }
}
