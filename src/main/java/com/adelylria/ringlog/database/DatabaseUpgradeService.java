package com.adelylria.ringlog.database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

import com.adelylria.ringlog.diagnostics.BuildInfo;
import com.adelylria.ringlog.diagnostics.SafeLog;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.MediaPathResolver;
import com.adelylria.ringlog.storage.MigrationProgressListener;
import com.adelylria.ringlog.storage.MigrationResult;
import com.adelylria.ringlog.storage.MigrationStatus;

/** Plans, safeguards, and executes typed database migrations in version order. */
public final class DatabaseUpgradeService {

    private final int targetSchema;
    private final SchemaMigrationRegistry registry;
    private final SchemaBackupService backupService;
    private final DatabaseIntegrityValidator integrityValidator;
    private final SchemaVersionDetector versionDetector;
    private final String appVersion;

    public DatabaseUpgradeService() {
        this(
                Database.SCHEMA_VERSION,
                new SchemaMigrationRegistry(List.of(
                        new V2ToV3SchemaMigration(),
                        new V3ToV4StorageMigration(),
                        new V4ToV5PlaceRegionMigration()
                )),
                new SchemaBackupService(),
                new DatabaseIntegrityValidator(),
                applicationVersion()
        );
    }

    /** Limited helper used inside an already isolated staging copy. */
    public static DatabaseUpgradeService forLegacyStaging() {
        return new DatabaseUpgradeService(
                3,
                new SchemaMigrationRegistry(List.of(new V2ToV3SchemaMigration())),
                new SchemaBackupService(),
                new DatabaseIntegrityValidator(),
                applicationVersion()
        );
    }

    DatabaseUpgradeService(
            int targetSchema,
            SchemaMigrationRegistry registry,
            SchemaBackupService backupService,
            DatabaseIntegrityValidator integrityValidator,
            String appVersion
    ) {
        this.targetSchema = targetSchema;
        this.registry = Objects.requireNonNull(registry, "registry");
        this.backupService = Objects.requireNonNull(backupService, "backupService");
        this.integrityValidator = Objects.requireNonNull(integrityValidator, "integrityValidator");
        this.versionDetector = new SchemaVersionDetector();
        this.appVersion = Objects.requireNonNull(appVersion, "appVersion");
    }

    public Inspection inspect(Path database, MediaPathResolver mediaResolver)
            throws SQLException {
        Path source = normalizedDatabase(database);
        Objects.requireNonNull(mediaResolver, "mediaResolver");
        DetectedSchema detected = versionDetector.detect(source);
        int logicalVersion = detected.logicalVersion();
        if (logicalVersion == 3) {
            return new Inspection(detected.pragmaVersion(), SchemaState.MEDIA_MIGRATION_REQUIRED);
        }
        if (logicalVersion == Database.SCHEMA_VERSION) {
            Database.validate(source.toString());
            try (Connection connection = Database.getReadOnlyConnection(source.toString())) {
                validateManagedReferences(connection, mediaResolver);
            }
            return new Inspection(detected.pragmaVersion(), SchemaState.READY);
        }
        if (logicalVersion == 2 || logicalVersion == 4) {
            return new Inspection(detected.pragmaVersion(), SchemaState.SQL_UPGRADE_REQUIRED);
        }
        throw new SQLException(
                "Versión de base de datos RingLog no compatible: " + logicalVersion
        );
    }

    public DatabaseUpgradeResult upgrade(AppPaths appPaths) throws DatabaseUpgradeException {
        AppPaths paths = Objects.requireNonNull(appPaths, "appPaths");
        Path database = paths.databasePath().toAbsolutePath().normalize();
        try {
            DetectedSchema detected = versionDetector.detect(database);
            int fromSchema = detected.logicalVersion();
            if (fromSchema > targetSchema) {
                throw new FutureSchemaVersionException(fromSchema, targetSchema);
            }

            SchemaUpgradePlan plan;
            try {
                plan = registry.plan(fromSchema, targetSchema);
            } catch (IllegalArgumentException | IllegalStateException unsupported) {
                throw new DatabaseUpgradeException(
                        "No existe una ruta segura de schema v" + fromSchema
                                + " a v" + targetSchema + '.',
                        unsupported
                );
            }

            if (plan.steps().isEmpty()) {
                validateTarget(database, paths, targetSchema);
                return new DatabaseUpgradeResult(
                        fromSchema, targetSchema, plan.steps(), null
                );
            }

            SafeLog.schemaUpgrade(fromSchema, targetSchema, plan.steps().size());

            SchemaBackup backup;
            long backupStarted = System.nanoTime();
            SafeLog.operationStarted("schema_backup");
            try {
                backup = backupService.create(
                        paths, database, fromSchema, targetSchema, appVersion
                );
                SafeLog.operationCompleted(
                        "schema_backup",
                        elapsedMillis(backupStarted),
                        backup.size()
                );
            } catch (SchemaBackupException failure) {
                SafeLog.failure("schema_backup", failure);
                throw new DatabaseUpgradeException(
                        "No se ha iniciado la actualización porque falló el backup de seguridad.",
                        failure
                );
            }

            long upgradeStarted = System.nanoTime();
            SafeLog.operationStarted("schema_upgrade");
            try {
                executePlan(plan, paths);
                validateTarget(database, paths, targetSchema);
                SafeLog.operationCompleted(
                        "schema_upgrade",
                        elapsedMillis(upgradeStarted),
                        plan.steps().size()
                );
            } catch (Exception failure) {
                SafeLog.failure("schema_upgrade", failure);
                throw failure;
            }
            return new DatabaseUpgradeResult(
                    fromSchema, targetSchema, plan.steps(), backup
            );
        } catch (FutureSchemaVersionException failure) {
            throw failure;
        } catch (DatabaseUpgradeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new DatabaseUpgradeException(
                    "No se pudo actualizar la base de datos de RingLog.", failure
            );
        }
    }

    /** Compatibility entry point for tests and isolated legacy staging. */
    public void upgradeV2ToV3(Path database) throws SQLException {
        Path target = normalizedDatabase(database);
        try (Connection connection = Database.getConnection(target.toString())) {
            executeSqlMigrations(connection, List.of(new V2ToV3SchemaMigration()), 2);
        }
    }

    private void executePlan(SchemaUpgradePlan plan, AppPaths paths)
            throws DatabaseUpgradeException {
        int index = 0;
        while (index < plan.steps().size()) {
            SchemaMigration migration = plan.steps().get(index);
            if (migration.executionType()
                    == SchemaMigrationExecutionType.SQL_TRANSACTIONAL) {
                java.util.ArrayList<SqlTransactionalMigration> sqlSteps = new java.util.ArrayList<>();
                int segmentSource = migration.fromSchema();
                while (index < plan.steps().size()
                        && plan.steps().get(index).executionType()
                        == SchemaMigrationExecutionType.SQL_TRANSACTIONAL) {
                    SchemaMigration step = plan.steps().get(index++);
                    if (!(step instanceof SqlTransactionalMigration sql)) {
                        throw new DatabaseUpgradeException(
                                "Una migración SQL registrada no implementa su contrato de ejecución."
                        );
                    }
                    sqlSteps.add(sql);
                }
                try (Connection connection = Database.getConnection(
                        paths.databasePath().toString()
                )) {
                    executeSqlMigrations(connection, sqlSteps, segmentSource);
                } catch (SQLException failure) {
                    throw new DatabaseUpgradeException(
                            "Falló la migración SQL; se han revertido todos sus pasos.", failure
                    );
                }
                continue;
            }

            if (!(migration instanceof StagedStorageMigration staged)) {
                throw new DatabaseUpgradeException(
                        "Una migración de almacenamiento no implementa su contrato de ejecución."
                );
            }
            try {
                MigrationResult result = staged.migrate(new StagedStorageMigrationContext(
                        paths,
                        MigrationProgressListener.NONE
                ));
                if (result.status() != MigrationStatus.COMPLETED) {
                    throw new DatabaseUpgradeException(
                            "La migración de medios necesita intervención antes de continuar."
                    );
                }
            } catch (DatabaseUpgradeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new DatabaseUpgradeException(
                        "Falló la migración segura de almacenamiento.", failure
                );
            }
            index++;
        }
    }

    private void executeSqlMigrations(
            Connection connection,
            List<? extends SqlTransactionalMigration> migrations,
            int sourceSchema
    ) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            int expectedSource = sourceSchema;
            for (SqlTransactionalMigration migration : migrations) {
                if (migration.fromSchema() != expectedSource) {
                    throw new SQLException("La cadena SQL no es contigua.");
                }
                migration.migrate(connection);
                setUserVersion(connection, migration.targetSchema());
                migration.validate(connection);
                expectedSource = migration.targetSchema();
            }
            integrityValidator.requireValid(connection, expectedSource);
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            rollback(connection, failure);
            throw failure;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static void validateTarget(Path database, AppPaths paths, int targetSchema)
            throws SQLException {
        if (targetSchema == Database.SCHEMA_VERSION) {
            Inspection inspection = new DatabaseUpgradeService().inspect(
                    database,
                    new MediaPathResolver(paths)
            );
            if (inspection.state() != SchemaState.READY) {
                throw new SQLException("La base de datos no alcanzó el schema gestionado actual.");
            }
            return;
        }
        try (Connection connection = Database.getReadOnlyConnection(database.toString())) {
            new DatabaseIntegrityValidator().requireValid(connection, targetSchema);
        }
    }

    private static void setUserVersion(Connection connection, int schema) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = " + schema);
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static void validateManagedReferences(
            Connection connection,
            MediaPathResolver resolver
    ) throws SQLException {
        validateReferences(connection, "event_photo", false, resolver);
        validateReferences(connection, "legacy_unassigned_photo", true, resolver);
    }

    private static void validateReferences(
            Connection connection,
            String table,
            boolean unassigned,
            MediaPathResolver resolver
    ) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT file_path FROM " + table + " WHERE file_path IS NOT NULL"
             )) {
            while (rows.next()) {
                try {
                    if (unassigned) {
                        resolver.resolveUnassignedPhoto(rows.getString(1));
                    } else {
                        resolver.resolveEventPhoto(rows.getString(1));
                    }
                } catch (IllegalArgumentException invalid) {
                    throw new SQLException(
                            "El schema gestionado contiene una referencia de medios no válida en "
                                    + table + '.',
                            invalid
                    );
                }
            }
        }
    }

    private static Path normalizedDatabase(Path database) throws SQLException {
        if (database == null) {
            throw new SQLException("No se ha indicado la base de datos de RingLog.");
        }
        Path normalized = database.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new SQLException("No existe la base de datos de RingLog.");
        }
        return normalized;
    }

    private static String applicationVersion() {
        return BuildInfo.applicationVersion();
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    public enum SchemaState {
        SQL_UPGRADE_REQUIRED,
        MEDIA_MIGRATION_REQUIRED,
        READY
    }

    public record Inspection(int userVersion, SchemaState state) {
    }
}
