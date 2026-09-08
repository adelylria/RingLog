package com.adelylria.ringlog.application;

import java.util.Objects;
import java.util.Optional;

import com.adelylria.ringlog.database.DatabaseAccess;
import com.adelylria.ringlog.database.ReadOnlyDatabaseAccess;
import com.adelylria.ringlog.database.WritableDatabaseAccess;
import com.adelylria.ringlog.importexport.service.ConflictResolutionService;
import com.adelylria.ringlog.preferences.ApplicationPreferences;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.DataPaths;
import com.adelylria.ringlog.storage.PortableAppPaths;
import com.adelylria.ringlog.ui.importexport.ImportExportController;

/** Fully composed runtime boundary for normal and portable RingLog. */
public final class ApplicationContext {

    private final ApplicationMode mode;
    private final ApplicationCapabilities capabilities;
    private final DataPaths paths;
    private final DatabaseAccess databaseAccess;
    private final ApplicationPreferences preferences;
    private final DataMutationCoordinator mutationCoordinator;
    private final ApplicationMutationServices mutationServices;

    private ApplicationContext(
            ApplicationMode mode,
            ApplicationCapabilities capabilities,
            DataPaths paths,
            DatabaseAccess databaseAccess,
            ApplicationPreferences preferences,
            DataMutationCoordinator mutationCoordinator,
            ApplicationMutationServices mutationServices
    ) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.databaseAccess = Objects.requireNonNull(databaseAccess, "databaseAccess");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.mutationCoordinator = Objects.requireNonNull(
                mutationCoordinator, "mutationCoordinator"
        );
        this.mutationServices = mutationServices;
    }

    public static ApplicationContext normal(AppPaths paths) {
        Objects.requireNonNull(paths, "paths");
        DataMutationCoordinator mutations = new DataMutationCoordinator();
        WritableDatabaseAccess database = new WritableDatabaseAccess(paths.databasePath());
        return new ApplicationContext(
                ApplicationMode.NORMAL,
                ApplicationCapabilities.normal(),
                paths,
                database,
                ApplicationPreferences.production(),
                mutations,
                new ApplicationMutationServices(
                        ImportExportController.application(paths, mutations),
                        new ConflictResolutionService(
                                paths.databasePath().toString(), mutations
                        )
                )
        );
    }

    public static ApplicationContext portable(PortableAppPaths paths) {
        Objects.requireNonNull(paths, "paths");
        return new ApplicationContext(
                ApplicationMode.PORTABLE_READ_ONLY,
                ApplicationCapabilities.portableReadOnly(),
                paths,
                new ReadOnlyDatabaseAccess(paths.databasePath()),
                new ApplicationPreferences(paths.preferencesPath()),
                new DataMutationCoordinator(),
                null
        );
    }

    public ApplicationMode mode() {
        return mode;
    }

    public ApplicationCapabilities capabilities() {
        return capabilities;
    }

    public DataPaths paths() {
        return paths;
    }

    public DatabaseAccess databaseAccess() {
        return databaseAccess;
    }

    public ApplicationPreferences preferences() {
        return preferences;
    }

    public DataMutationCoordinator mutationCoordinator() {
        return mutationCoordinator;
    }

    public Optional<ApplicationMutationServices> mutationServices() {
        return Optional.ofNullable(mutationServices);
    }

    public boolean portableReadOnly() {
        return mode == ApplicationMode.PORTABLE_READ_ONLY;
    }
}
