package com.adelylria.ringlog.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.database.DatabaseUpgradeException;
import com.adelylria.ringlog.database.DatabaseUpgradeService;

/** Determines the safe storage startup path without making ambiguous choices. */
public final class ApplicationStorageBootstrap {

    private final AppPaths paths;
    private final LegacyDataLocationDetector detector;

    public ApplicationStorageBootstrap(
            AppPaths paths,
            LegacyDataLocationDetector detector
    ) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.detector = Objects.requireNonNull(detector, "detector");
    }

    public StartupStorageResult prepare()
            throws IOException, SQLException, DatabaseUpgradeException {
        DataLocationAssessment assessment = detector.detect();
        return switch (assessment.state()) {
            case NO_DATABASE -> initializeFresh(assessment);
            case LEGACY_ONLY -> new StartupStorageResult(
                    StartupStorageStatus.MIGRATION_REQUIRED,
                    null,
                    assessment.legacyDatabase().path(),
                    assessment
            );
            case MANAGED_ONLY, BOTH_IDENTICAL -> inspectManaged(assessment);
            case BOTH_DIFFERENT -> new MigrationReceiptStore(paths).matches(
                    assessment.legacyDatabase(), assessment.managedDatabase()
            ) ? inspectManaged(assessment) : new StartupStorageResult(
                StartupStorageStatus.USER_DECISION_REQUIRED,
                null,
                assessment.legacyDatabase().path(),
                assessment
            );
        };
    }

    public StartupStorageResult useManaged(DataLocationAssessment assessment)
            throws IOException, SQLException, DatabaseUpgradeException {
        return inspectManaged(Objects.requireNonNull(assessment, "assessment"));
    }

    public StartupStorageResult startFresh(DataLocationAssessment assessment)
            throws IOException, SQLException, DatabaseUpgradeException {
        if (Files.exists(paths.databasePath())) {
            throw new IOException("No se puede crear una base nueva sobre datos existentes.");
        }
        return initializeFresh(Objects.requireNonNull(assessment, "assessment"));
    }

    private StartupStorageResult initializeFresh(DataLocationAssessment assessment)
            throws IOException, SQLException {
        createDirectories();
        Database.initialize(paths.databasePath().toString());
        Database.validate(paths.databasePath().toString());
        return new StartupStorageResult(
                StartupStorageStatus.READY,
                paths.databasePath(),
                null,
                assessment
        );
    }

    private StartupStorageResult inspectManaged(DataLocationAssessment assessment)
            throws IOException, SQLException, DatabaseUpgradeException {
        createDirectories();
        Path database = paths.databasePath();
        new DatabaseUpgradeService().upgrade(paths);
        return new StartupStorageResult(
                StartupStorageStatus.READY, database, null, assessment
        );
    }

    private void createDirectories() throws IOException {
        for (Path directory : new Path[]{
                paths.databasePath().getParent(),
                paths.eventPhotosDirectory(),
                paths.nativePhotosDirectory(),
                paths.unassignedPhotosDirectory(),
                paths.backupsDirectory(),
                paths.migrationDirectory()
        }) {
            Files.createDirectories(directory);
        }
        try {
            Files.createDirectories(paths.logsDirectory());
        } catch (IOException loggingOnlyFailure) {
            // Persistent logging is diagnostic and must never block database startup by itself.
        }
    }
}
