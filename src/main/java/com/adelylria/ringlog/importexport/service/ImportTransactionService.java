package com.adelylria.ringlog.importexport.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.database.entity.BirdEntity;
import com.adelylria.ringlog.database.entity.BirdEventEntity;
import com.adelylria.ringlog.database.entity.EventPhotoEntity;
import com.adelylria.ringlog.database.entity.EventSourceAliasEntity;
import com.adelylria.ringlog.database.entity.ImportBatchEntity;
import com.adelylria.ringlog.database.entity.ImportMetadataEntity;
import com.adelylria.ringlog.database.entity.LegacySourceRecordEntity;
import com.adelylria.ringlog.database.entity.LegacyUnassignedPhotoEntity;
import com.adelylria.ringlog.database.entity.MigrationAuditEntity;
import com.adelylria.ringlog.database.entity.MigrationConflictEntity;
import com.adelylria.ringlog.database.entity.MigrationWarningEntity;
import com.adelylria.ringlog.database.entity.PlaceEntity;
import com.adelylria.ringlog.database.entity.SpeciesEntity;
import com.adelylria.ringlog.importexport.ImportExecutionException;
import com.adelylria.ringlog.importexport.ImportFormat;
import com.adelylria.ringlog.importexport.ImportPlan;
import com.adelylria.ringlog.importexport.compatibility.RingLogV3BackupModel;
import com.adelylria.ringlog.importexport.legacy.LegacyImportModel;
import com.adelylria.ringlog.importexport.nativeformat.NativeExportModel;
import com.adelylria.ringlog.importexport.nativeformat.NativeExportModel.NativeRow;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.MediaPathResolver;
import com.adelylria.ringlog.storage.PhotoArea;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.dao.GenericRawResults;
import com.j256.ormlite.misc.TransactionManager;
import com.j256.ormlite.stmt.QueryBuilder;
import com.j256.ormlite.support.ConnectionSource;

/** Applies a validated import plan atomically, without reinterpreting authoritative rows. */
public final class ImportTransactionService {

    private final String databaseFile;
    private final AppPaths appPaths;
    private final MediaPathResolver mediaResolver;

    public ImportTransactionService(String databaseFile, Path dataRoot) {
        this(databaseFile, AppPaths.forDataRoot(dataRoot));
    }

    public ImportTransactionService(String databaseFile, AppPaths appPaths) {
        if (databaseFile == null || databaseFile.isBlank()) {
            throw new IllegalArgumentException("Indica la base de datos de destino.");
        }
        this.databaseFile = databaseFile;
        this.appPaths = Objects.requireNonNull(appPaths, "appPaths");
        this.mediaResolver = new MediaPathResolver(appPaths);
    }

    public ImportExecutionResult execute(ImportPlan plan) throws ImportExecutionException {
        if (plan == null || !Set.of(
                ImportFormat.LEGACY_V5, ImportFormat.RINGLOG_BACKUP_V3,
                ImportFormat.RINGLOG_EXPORT_V1
        ).contains(plan.profile().format())) {
            throw new ImportExecutionException("El plan no contiene un formato ejecutable.");
        }
        try {
            Database.initialize(databaseFile);
            ImportExecutionResult existing = plan.profile().format()
                    == ImportFormat.RINGLOG_EXPORT_V1
                    ? existingNativeResult(plan.nativeModel().exportId())
                    : existingResult(plan.fingerprint());
            if (existing != null) {
                return existing;
            }
        } catch (SQLException exception) {
            throw new ImportExecutionException("No se pudo preparar la base de datos.", exception);
        }

        return switch (plan.profile().format()) {
            case LEGACY_V5 -> executeLegacy(plan);
            case RINGLOG_BACKUP_V3 -> executeBackupV3(plan);
            case RINGLOG_EXPORT_V1 -> executeNative(plan);
        };
    }

    private ImportExecutionResult executeLegacy(ImportPlan plan)
            throws ImportExecutionException {
        PhotoStorageService storage;
        try {
            storage = PhotoStorageService.stage(appPaths, plan.legacyModel());
        } catch (IOException exception) {
            throw new ImportExecutionException(
                    "No se pudieron preparar las fotografías de la importación.", exception
            );
        }

        ImportExecutionResult result;
        try {
            result = Database.withOrm(databaseFile, source ->
                    TransactionManager.callInTransaction(source,
                            () -> executeInTransaction(
                                    source, plan, storage, mediaResolver
                            ))
            );
        } catch (SQLException | RuntimeException exception) {
            storage.rollbackPublished();
            try {
                storage.close();
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw new ImportExecutionException(
                    "La importación no se pudo completar y se ha revertido.", exception
            );
        }
        try {
            storage.close();
        } catch (IOException cleanupFailure) {
            // The database and published binaries are already committed. A stale private
            // staging directory is harmless and must not turn success into data loss.
            com.adelylria.ringlog.diagnostics.SafeLog.failure(
                    "import_temp_cleanup", cleanupFailure
            );
        }
        return result;
    }

    private ImportExecutionResult executeBackupV3(ImportPlan plan)
            throws ImportExecutionException {
        BackupPhotoStorageService storage;
        try {
            storage = BackupPhotoStorageService.stage(appPaths, plan.backupV3Model());
        } catch (IOException exception) {
            throw new ImportExecutionException(
                    "No se pudieron preparar las fotos incrustadas de la copia.", exception
            );
        }

        ImportExecutionResult result;
        try {
            result = Database.withOrm(databaseFile, source ->
                    TransactionManager.callInTransaction(source,
                            () -> executeBackupInTransaction(
                                    source, plan, storage, mediaResolver
                            ))
            );
        } catch (SQLException | RuntimeException exception) {
            storage.rollbackPublished();
            try {
                storage.close();
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw new ImportExecutionException(
                    "La restauración de la copia se ha revertido por completo.", exception
            );
        }
        try {
            storage.close();
        } catch (IOException cleanupFailure) {
            com.adelylria.ringlog.diagnostics.SafeLog.failure(
                    "backup_temp_cleanup", cleanupFailure
            );
        }
        return result;
    }

    private ImportExecutionResult executeNative(ImportPlan plan)
            throws ImportExecutionException {
        NativePhotoStorageService storage;
        try {
            storage = NativePhotoStorageService.stage(appPaths, plan.nativeModel());
        } catch (IOException exception) {
            throw new ImportExecutionException(
                    "No se pudieron preparar los binarios del export nativo.", exception
            );
        }
        ImportExecutionResult result;
        try {
            result = Database.withOrm(databaseFile, source ->
                    TransactionManager.callInTransaction(source,
                            () -> executeNativeInTransaction(
                                    source, plan, storage, mediaResolver
                            ))
            );
        } catch (SQLException | RuntimeException exception) {
            storage.rollbackPublished();
            try {
                storage.close();
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw new ImportExecutionException(
                    "La restauración nativa se ha revertido por completo.", exception
            );
        }
        try {
            storage.close();
        } catch (IOException cleanupFailure) {
            com.adelylria.ringlog.diagnostics.SafeLog.failure(
                    "native_temp_cleanup", cleanupFailure
            );
        }
        return result;
    }

    private ImportExecutionResult existingResult(String fingerprint) throws SQLException {
        return Database.withOrm(databaseFile, source -> {
            Dao<ImportBatchEntity, Long> dao = dao(source, ImportBatchEntity.class);
            ImportBatchEntity existing = first(dao, "fingerprint", fingerprint);
            return existing == null ? null : new ImportExecutionResult(
                    ImportExecutionResult.Status.ALREADY_IMPORTED, existing.id()
            );
        });
    }

    private ImportExecutionResult existingNativeResult(String exportId) throws SQLException {
        return Database.withOrm(databaseFile, source -> {
            Dao<ImportBatchEntity, Long> dao = dao(source, ImportBatchEntity.class);
            ImportBatchEntity existing = first(dao, "export_id", exportId);
            return existing == null ? null : new ImportExecutionResult(
                    ImportExecutionResult.Status.ALREADY_IMPORTED, existing.id()
            );
        });
    }

    private static ImportExecutionResult executeNativeInTransaction(
            ConnectionSource source,
            ImportPlan plan,
            NativePhotoStorageService storage,
            MediaPathResolver resolver
    ) throws Exception {
        Dao<ImportBatchEntity, Long> anchor = dao(source, ImportBatchEntity.class);
        ImportBatchEntity duplicate = first(
                anchor, "export_id", plan.nativeModel().exportId()
        );
        if (duplicate != null) {
            return new ImportExecutionResult(
                    ImportExecutionResult.Status.ALREADY_IMPORTED, duplicate.id()
            );
        }
        NativeExportModel model = plan.nativeModel();
        Map<String, Long> species = restoreNativeSpecies(anchor, model);
        Map<String, Long> birds = restoreNativeBirds(anchor, model, species);
        Map<String, Long> places = restoreNativePlaces(anchor, model);
        Map<String, Long> events = restoreNativeEvents(anchor, model, birds, places);
        Map<String, Long> batches = restoreNativeBatches(anchor, model);
        restoreNativeMetadata(anchor, model, batches);
        restoreNativeAliases(anchor, model, events);
        restoreNativeSourceRecords(anchor, model, batches);
        restoreNativeUnassigned(anchor, model, batches, storage, resolver);
        restoreNativeAudit(anchor, model, batches);
        restoreNativeConflicts(anchor, model, batches, events);
        restoreNativeWarnings(anchor, model, batches);
        restoreNativePhotos(anchor, model, events, storage, resolver);

        Map<String, String> receipt = linkedValues();
        String stableKey = UUID.randomUUID().toString();
        receipt.put("stable_key", stableKey);
        receipt.put("source_format", "RingLog Export");
        receipt.put("format_version", "1");
        receipt.put("source_name", "RingLog native restore");
        receipt.put("source_reference", plan.source().sourcePath().toString());
        receipt.put("fingerprint", plan.fingerprint());
        receipt.put("export_id", model.exportId());
        receipt.put("import_mode", "NATIVE_RESTORE");
        receipt.put("imported_at", Instant.now().toString());
        insertOrVerify(anchor, "import_batch", "stable_key = ?",
                new String[]{stableKey}, receipt);
        long receiptId = queryId(anchor, "import_batch", stableKey);
        return new ImportExecutionResult(ImportExecutionResult.Status.APPLIED, receiptId);
    }

    private static Map<String, Long> restoreNativeSpecies(
            Dao<ImportBatchEntity, Long> anchor,
            NativeExportModel model
    ) throws SQLException {
        Map<String, Long> result = new HashMap<>();
        for (NativeRow row : model.rows("species")) {
            Map<String, String> values = linkedValues();
            copy(values, row, "stable_key", "code", "scientific_name", "common_name",
                    "active", "created_at", "updated_at");
            insertOrVerify(anchor, "species", "stable_key = ?",
                    new String[]{row.value("stable_key")}, values);
            result.put(row.value("stable_key"),
                    queryId(anchor, "species", row.value("stable_key")));
        }
        return result;
    }

    private static Map<String, Long> restoreNativeBirds(
            Dao<ImportBatchEntity, Long> anchor,
            NativeExportModel model,
            Map<String, Long> species
    ) throws SQLException {
        Map<String, Long> result = new HashMap<>();
        for (NativeRow row : model.rows("birds")) {
            Map<String, String> values = linkedValues();
            values.put("stable_key", row.value("stable_key"));
            values.put("ring_number", row.value("ring_number"));
            values.put("species_id", Long.toString(requiredId(
                    species, row.value("species_stable_key"), "especie"
            )));
            values.put("created_at", row.value("created_at"));
            values.put("updated_at", row.value("updated_at"));
            insertOrVerify(anchor, "bird", "stable_key = ?",
                    new String[]{row.value("stable_key")}, values);
            result.put(row.value("stable_key"),
                    queryId(anchor, "bird", row.value("stable_key")));
        }
        return result;
    }

    private static Map<String, Long> restoreNativePlaces(
            Dao<ImportBatchEntity, Long> anchor,
            NativeExportModel model
    ) throws SQLException {
        Map<String, Long> result = new HashMap<>();
        for (NativeRow row : model.rows("places")) {
            Map<String, String> values = linkedValues();
            copy(values, row, "stable_key", "name", "locality", "latitude", "longitude",
                    "notes", "is_favorite", "is_default", "active", "created_at", "updated_at");
            insertOrVerify(anchor, "place", "stable_key = ?",
                    new String[]{row.value("stable_key")}, values);
            result.put(row.value("stable_key"),
                    queryId(anchor, "place", row.value("stable_key")));
        }
        return result;
    }

    private static Map<String, Long> restoreNativeEvents(
            Dao<ImportBatchEntity, Long> anchor,
            NativeExportModel model,
            Map<String, Long> birds,
            Map<String, Long> places
    ) throws SQLException {
        Map<String, Long> result = new HashMap<>();
        for (NativeRow row : model.rows("events")) {
            Map<String, String> values = linkedValues();
            values.put("stable_key", row.value("stable_key"));
            values.put("migration_key", row.value("migration_key"));
            values.put("bird_id", Long.toString(requiredId(
                    birds, row.value("bird_stable_key"), "ave"
            )));
            copy(values, row, "event_type", "event_date", "event_time");
            values.put("place_id", row.value("place_stable_key") == null ? null
                    : Long.toString(requiredId(places, row.value("place_stable_key"), "lugar")));
            copy(values, row, "location_text", "sex_code", "age_euring_code", "fat_score",
                    "muscle_score", "ringer_initials", "status", "reproductive_status",
                    "moult_intensity", "moult_extension", "bird_condition", "return_status",
                    "wing", "p3", "torso", "weight", "observations", "clouds", "rain",
                    "thermal_sensation", "wind", "capture_type", "is_dead", "source_name",
                    "source_reference", "review_status", "review_note", "created_at", "updated_at");
            insertOrVerify(anchor, "bird_event", "stable_key = ?",
                    new String[]{row.value("stable_key")}, values);
            result.put(row.value("stable_key"),
                    queryId(anchor, "bird_event", row.value("stable_key")));
        }
        return result;
    }

    private static Map<String, Long> restoreNativeBatches(
            Dao<ImportBatchEntity, Long> anchor,
            NativeExportModel model
    ) throws SQLException {
        Map<String, Long> result = new HashMap<>();
        for (NativeRow row : model.rows("import_batches")) {
            Map<String, String> values = linkedValues();
            copy(values, row, "stable_key", "source_format", "format_version", "source_name",
                    "source_reference", "fingerprint", "export_id", "import_mode", "imported_at");
            insertOrVerify(anchor, "import_batch", "stable_key = ?",
                    new String[]{row.value("stable_key")}, values);
            result.put(row.value("stable_key"),
                    queryId(anchor, "import_batch", row.value("stable_key")));
        }
        return result;
    }

    private static void restoreNativeMetadata(
            Dao<ImportBatchEntity, Long> anchor,
            NativeExportModel model,
            Map<String, Long> batches
    ) throws SQLException {
        for (NativeRow row : model.rows("import_metadata")) {
            String batchId = Long.toString(requiredId(
                    batches, row.value("import_batch_stable_key"), "lote"
            ));
            Map<String, String> values = linkedValues();
            values.put("import_batch_id", batchId);
            values.put("metadata_key", row.value("metadata_key"));
            values.put("metadata_value", row.value("metadata_value"));
            insertOrVerify(anchor, "import_metadata",
                    "import_batch_id = ? AND metadata_key = ?",
                    new String[]{batchId, row.value("metadata_key")}, values);
        }
    }

    private static void restoreNativeAliases(
            Dao<ImportBatchEntity, Long> anchor,
            NativeExportModel model,
            Map<String, Long> events
    ) throws SQLException {
        for (NativeRow row : model.rows("event_source_aliases")) {
            Map<String, String> values = linkedValues();
            values.put("stable_key", row.value("stable_key"));
            values.put("event_id", Long.toString(requiredId(
                    events, row.value("event_stable_key"), "evento"
            )));
            copy(values, row, "source_name", "source_reference", "created_at");
            insertOrVerify(anchor, "event_source_alias", "stable_key = ?",
                    new String[]{row.value("stable_key")}, values);
        }
    }

    private static void restoreNativeSourceRecords(
            Dao<ImportBatchEntity, Long> anchor,
            NativeExportModel model,
            Map<String, Long> batches
    ) throws SQLException {
        for (NativeRow row : model.rows("source_records")) {
            Map<String, String> values = linkedValues();
            values.put("stable_key", row.value("stable_key"));
            values.put("import_batch_id", Long.toString(requiredId(
                    batches, row.value("import_batch_stable_key"), "lote"
            )));
            copy(values, row, "source_name", "source_section", "source_reference",
                    "ring_number", "raw_payload", "created_at");
            insertOrVerify(anchor, "legacy_source_record", "stable_key = ?",
                    new String[]{row.value("stable_key")}, values);
        }
    }

    private static void restoreNativeUnassigned(
            Dao<ImportBatchEntity, Long> anchor,
            NativeExportModel model,
            Map<String, Long> batches,
            NativePhotoStorageService storage,
            MediaPathResolver resolver
    ) throws SQLException, IOException {
        for (NativeRow row : model.rows("unassigned_photos")) {
            Map<String, String> values = linkedValues();
            values.put("stable_key", row.value("stable_key"));
            values.put("import_batch_id", Long.toString(requiredId(
                    batches, row.value("import_batch_stable_key"), "lote"
            )));
            copy(values, row, "source_name", "source_reference", "file_name");
            copy(values, row, "mime_type", "content_sha256", "content_size", "width",
                    "height", "created_at");
            Long existingId = queryOptionalId(
                    anchor, "legacy_unassigned_photo", row.value("stable_key")
            );
            if (existingId != null) {
                verifyExisting(anchor, "legacy_unassigned_photo", "stable_key = ?",
                        new String[]{row.value("stable_key")}, values);
                repairNativeMediaIfNeeded(
                        anchor, "legacy_unassigned_photo", existingId, row, storage, resolver
                );
                continue;
            }
            values.put("file_path", row.value("media_path") == null
                    ? null : resolver.toUnassignedReference(
                            storage.publish(row.value("media_path"))
                    ));
            insertOrVerify(anchor, "legacy_unassigned_photo", "stable_key = ?",
                    new String[]{row.value("stable_key")}, values);
        }
    }

    private static void restoreNativeAudit(
            Dao<ImportBatchEntity, Long> anchor,
            NativeExportModel model,
            Map<String, Long> batches
    ) throws SQLException {
        for (NativeRow row : model.rows("migration_audit")) {
            Map<String, String> values = linkedValues();
            values.put("stable_key", row.value("stable_key"));
            values.put("import_batch_id", Long.toString(requiredId(
                    batches, row.value("import_batch_stable_key"), "lote"
            )));
            copy(values, row, "source_name", "source_section", "source_reference",
                    "source_field", "original_value", "original_display", "destination",
                    "normalized_value", "status", "note");
            insertOrVerify(anchor, "migration_audit", "stable_key = ?",
                    new String[]{row.value("stable_key")}, values);
        }
    }

    private static void restoreNativeConflicts(
            Dao<ImportBatchEntity, Long> anchor,
            NativeExportModel model,
            Map<String, Long> batches,
            Map<String, Long> events
    ) throws SQLException {
        for (NativeRow row : model.rows("migration_conflicts")) {
            Map<String, String> values = linkedValues();
            values.put("stable_key", row.value("stable_key"));
            values.put("conflict_key", row.value("conflict_key"));
            values.put("import_batch_id", Long.toString(requiredId(
                    batches, row.value("import_batch_stable_key"), "lote"
            )));
            values.put("event_id", row.value("event_stable_key") == null ? null
                    : Long.toString(requiredId(events, row.value("event_stable_key"), "evento")));
            copy(values, row, "ring_number", "conflict_type", "event_type", "field_name",
                    "canonical_value", "alternative_value", "canonical_source_reference",
                    "alternative_source_reference", "canonical_snapshot", "alternative_snapshot",
                    "original_note", "status", "resolution_type", "resolution_value",
                    "resolved_at", "resolution_notes", "created_at");
            insertOrVerify(anchor, "migration_conflict", "stable_key = ?",
                    new String[]{row.value("stable_key")}, values);
        }
    }

    private static void restoreNativeWarnings(
            Dao<ImportBatchEntity, Long> anchor,
            NativeExportModel model,
            Map<String, Long> batches
    ) throws SQLException {
        for (NativeRow row : model.rows("migration_warnings")) {
            Map<String, String> values = linkedValues();
            values.put("stable_key", row.value("stable_key"));
            values.put("import_batch_id", Long.toString(requiredId(
                    batches, row.value("import_batch_stable_key"), "lote"
            )));
            copy(values, row, "severity", "source", "reference", "message");
            insertOrVerify(anchor, "migration_warning", "stable_key = ?",
                    new String[]{row.value("stable_key")}, values);
        }
    }

    private static void restoreNativePhotos(
            Dao<ImportBatchEntity, Long> anchor,
            NativeExportModel model,
            Map<String, Long> events,
            NativePhotoStorageService storage,
            MediaPathResolver resolver
    ) throws SQLException, IOException {
        for (NativeRow row : model.rows("photos")) {
            Map<String, String> values = linkedValues();
            values.put("stable_key", row.value("stable_key"));
            values.put("event_id", Long.toString(requiredId(
                    events, row.value("event_stable_key"), "evento"
            )));
            values.put("file_name", row.value("file_name"));
            copy(values, row, "mime_type", "source_name", "source_reference",
                    "content_sha256", "content_size", "created_at");
            Long existingId = queryOptionalId(anchor, "event_photo", row.value("stable_key"));
            if (existingId != null) {
                verifyExisting(anchor, "event_photo", "stable_key = ?",
                        new String[]{row.value("stable_key")}, values);
                repairNativeMediaIfNeeded(
                        anchor, "event_photo", existingId, row, storage, resolver
                );
                continue;
            }
            values.put("file_path", resolver.toEventReference(
                    PhotoArea.NATIVE, storage.publish(row.value("media_path"))
            ));
            insertOrVerify(anchor, "event_photo", "stable_key = ?",
                    new String[]{row.value("stable_key")}, values);
        }
    }

    private static void repairNativeMediaIfNeeded(
            Dao<ImportBatchEntity, Long> anchor,
            String table,
            long id,
            NativeRow row,
            NativePhotoStorageService storage,
            MediaPathResolver resolver
    ) throws SQLException, IOException {
        String packagePath = row.value("media_path");
        if (packagePath == null) {
            return;
        }
        String current = querySingleValue(
                anchor, "SELECT file_path FROM " + table + " WHERE id = ?",
                Long.toString(id)
        );
        long packageSize;
        try {
            packageSize = Long.parseLong(row.value("package_size"));
        } catch (NumberFormatException exception) {
            throw new SQLException("Tamaño de binario nativo no válido.", exception);
        }
        Path currentFile = managedPath(resolver, table, current);
        if (NativePhotoStorageService.matches(
                currentFile, row.value("package_sha256"), packageSize
        )) {
            return;
        }
        Path repaired = storage.publish(packagePath);
        String reference = "legacy_unassigned_photo".equals(table)
                ? resolver.toUnassignedReference(repaired)
                : resolver.toEventReference(PhotoArea.NATIVE, repaired);
        anchor.executeRaw(
                "UPDATE " + table + " SET file_path = ? WHERE id = ?",
                reference, Long.toString(id)
        );
    }

    private static ImportExecutionResult executeInTransaction(
            ConnectionSource source,
            ImportPlan plan,
            PhotoStorageService storage,
            MediaPathResolver resolver
    ) throws Exception {
        Dao<ImportBatchEntity, Long> batchDao = dao(source, ImportBatchEntity.class);
        ImportBatchEntity existing = first(batchDao, "fingerprint", plan.fingerprint());
        if (existing != null) {
            return new ImportExecutionResult(
                    ImportExecutionResult.Status.ALREADY_IMPORTED, existing.id()
            );
        }

        LegacyImportModel model = plan.legacyModel();
        String importedAt = Instant.now().toString();
        ImportBatchEntity batch = new ImportBatchEntity();
        batch.setStableKey(UUID.randomUUID().toString());
        batch.setSourceFormat(plan.profile().metadata().get("format"));
        batch.setFormatVersion(plan.profile().metadata().get("format_version"));
        batch.setSourceName("RingLogLegacyMigrator");
        batch.setSourceReference(plan.source().sourcePath().toString());
        batch.setFingerprint(plan.fingerprint());
        batch.setImportMode("LEGACY_MIGRATION");
        batch.setImportedAt(importedAt);
        batchDao.create(batch);

        persistMetadata(source, batch.id(), plan.profile().metadata());
        Map<String, Long> speciesIds = mergeSpecies(source, model);
        Map<String, Long> birdIds = mergeBirds(source, model, speciesIds);
        Map<String, Long> placeIds = mergePlaces(source, model);
        Map<String, Long> eventIds = mergeEvents(source, model, birdIds, placeIds);
        persistAliases(source, model, eventIds);
        persistPhotos(source, model, eventIds, storage, resolver);
        persistSourceRecords(source, batch.id(), model);
        persistUnassignedPhotos(
                source, batch.id(), model, storage, resolver, importedAt
        );
        persistAudit(source, batch.id(), model);
        persistWarnings(source, batch.id(), model);
        persistConflicts(source, batch.id(), model, eventIds, importedAt);
        recomputeImportedReviews(source, eventIds.values());

        return new ImportExecutionResult(ImportExecutionResult.Status.APPLIED, batch.id());
    }

    private static ImportExecutionResult executeBackupInTransaction(
            ConnectionSource source,
            ImportPlan plan,
            BackupPhotoStorageService storage,
            MediaPathResolver resolver
    ) throws Exception {
        Dao<ImportBatchEntity, Long> batchDao = dao(source, ImportBatchEntity.class);
        ImportBatchEntity existing = first(batchDao, "fingerprint", plan.fingerprint());
        if (existing != null) {
            return new ImportExecutionResult(
                    ImportExecutionResult.Status.ALREADY_IMPORTED, existing.id()
            );
        }

        RingLogV3BackupModel model = plan.backupV3Model();
        ImportBatchEntity batch = new ImportBatchEntity();
        batch.setStableKey(UUID.randomUUID().toString());
        batch.setSourceFormat(model.metadata().get("format"));
        batch.setFormatVersion(model.metadata().get("format_version"));
        batch.setSourceName("RingLog backup v3");
        batch.setSourceReference(plan.source().sourcePath().toString());
        batch.setFingerprint(plan.fingerprint());
        batch.setImportMode("BACKUP_RESTORE");
        batch.setImportedAt(Instant.now().toString());
        batchDao.create(batch);
        persistMetadata(source, batch.id(), model.metadata());

        Map<String, Long> speciesIds = restoreBackupSpecies(source, model);
        Map<String, Long> birdIds = restoreBackupBirds(source, model, speciesIds);
        Map<String, Long> placeIds = restoreBackupPlaces(source, model);
        Map<String, Long> eventIds = restoreBackupEvents(
                source, model, birdIds, placeIds
        );
        restoreBackupAliases(source, model, eventIds);
        restoreBackupPhotos(source, model, eventIds, storage, resolver);
        persistBackupWarnings(source, batch.id(), model);
        return new ImportExecutionResult(ImportExecutionResult.Status.APPLIED, batch.id());
    }

    private static Map<String, Long> restoreBackupSpecies(
            ConnectionSource source,
            RingLogV3BackupModel model
    ) throws SQLException {
        Dao<SpeciesEntity, Long> dao = dao(source, SpeciesEntity.class);
        Map<String, Long> result = new HashMap<>();
        for (RingLogV3BackupModel.SpeciesRow row : model.species()) {
            SpeciesEntity entity = first(dao, "stable_key", row.stableKey());
            if (entity == null) {
                SpeciesEntity byCode = row.code() == null ? null : first(dao, "code", row.code());
                SpeciesEntity byName = first(dao, "scientific_name", row.scientificName());
                if (byCode != null && byName != null && byCode.id() != byName.id()) {
                    throw conflict("especie", row.stableKey());
                }
                entity = byCode != null ? byCode : byName;
            }
            if (entity == null) {
                entity = new SpeciesEntity(row.code(), row.scientificName(), row.commonName());
                entity.setStableKey(row.stableKey());
                entity.setActive(row.active());
                dao.create(entity);
                restoreTimestamps(dao, SpeciesEntity.TABLE, entity.id(),
                        row.createdAt(), row.updatedAt());
            }
            result.put(row.stableKey(), entity.id());
        }
        return result;
    }

    private static Map<String, Long> restoreBackupBirds(
            ConnectionSource source,
            RingLogV3BackupModel model,
            Map<String, Long> speciesIds
    ) throws SQLException {
        Dao<BirdEntity, Long> dao = dao(source, BirdEntity.class);
        Map<String, Long> result = new HashMap<>();
        for (RingLogV3BackupModel.BirdRow row : model.birds()) {
            long speciesId = requiredId(speciesIds, row.speciesStableKey(), "especie");
            BirdEntity entity = first(dao, "ring_number", row.ringNumber());
            if (entity == null) {
                entity = new BirdEntity(row.ringNumber(), speciesId);
                dao.create(entity);
                restoreTimestamps(dao, BirdEntity.TABLE, entity.id(),
                        row.createdAt(), row.updatedAt());
            } else if (entity.getSpeciesId() != speciesId) {
                throw conflict("ave", row.ringNumber());
            }
            result.put(row.ringNumber(), entity.id());
        }
        return result;
    }

    private static Map<String, Long> restoreBackupPlaces(
            ConnectionSource source,
            RingLogV3BackupModel model
    ) throws SQLException {
        Dao<PlaceEntity, Long> dao = dao(source, PlaceEntity.class);
        Map<String, Long> result = new HashMap<>();
        for (RingLogV3BackupModel.PlaceRow row : model.places()) {
            PlaceEntity entity = first(dao, "stable_key", row.stableKey());
            if (entity == null) {
                QueryBuilder<PlaceEntity, Long> query = dao.queryBuilder();
                if (row.locality() == null) {
                    query.where().eq("name", row.name()).and().isNull("locality");
                } else {
                    query.where().eq("name", row.name()).and().eq("locality", row.locality());
                }
                entity = dao.queryForFirst(query.prepare());
            }
            if (entity == null) {
                entity = new PlaceEntity(
                        row.name(), row.locality(), row.latitude(), row.longitude(), row.notes(),
                        row.favorite(), row.defaultPlace()
                );
                entity.setStableKey(row.stableKey());
                entity.setActive(row.active());
                dao.create(entity);
                restoreTimestamps(dao, PlaceEntity.TABLE, entity.id(),
                        row.createdAt(), row.updatedAt());
            }
            result.put(row.stableKey(), entity.id());
        }
        return result;
    }

    private static Map<String, Long> restoreBackupEvents(
            ConnectionSource source,
            RingLogV3BackupModel model,
            Map<String, Long> birdIds,
            Map<String, Long> placeIds
    ) throws SQLException {
        Dao<BirdEventEntity, Long> dao = dao(source, BirdEventEntity.class);
        Map<String, Long> result = new HashMap<>();
        for (RingLogV3BackupModel.EventRow row : model.events()) {
            BirdEventEntity entity = first(dao, "stable_key", row.stableKey());
            if (entity == null) {
                long birdId = requiredId(birdIds, row.ringNumber(), "ave");
                Long placeId = row.placeStableKey() == null
                        ? null : requiredId(placeIds, row.placeStableKey(), "lugar");
                entity = backupEventEntity(row, birdId, placeId);
                dao.create(entity);
                restoreTimestamps(dao, BirdEventEntity.TABLE, entity.id(),
                        row.createdAt(), row.updatedAt());
            }
            result.put(row.stableKey(), entity.id());
        }
        return result;
    }

    private static BirdEventEntity backupEventEntity(
            RingLogV3BackupModel.EventRow row,
            long birdId,
            Long placeId
    ) {
        BirdEventEntity entity = new BirdEventEntity();
        entity.setStableKey(row.stableKey());
        entity.setBirdId(birdId);
        entity.setEventType(row.eventType());
        entity.setEventDate(row.eventDate());
        entity.setEventTime(row.eventTime());
        entity.setPlaceId(placeId);
        entity.setLocationText(row.locationText());
        entity.setSexCode(row.sexCode());
        entity.setAgeEuringCode(row.ageEuringCode());
        entity.setFatScore(row.fatScore());
        entity.setMuscleScore(row.muscleScore());
        entity.setRingerInitials(row.ringerInitials());
        entity.setStatus(row.status());
        entity.setReproductiveStatus(row.reproductiveStatus());
        entity.setMoultIntensity(row.moultIntensity());
        entity.setMoultExtension(row.moultExtension());
        entity.setBirdCondition(row.birdCondition());
        entity.setReturnStatus(row.returnStatus());
        entity.setWing(row.wing());
        entity.setP3(row.p3());
        entity.setTorso(row.torso());
        entity.setWeight(row.weight());
        entity.setObservations(row.observations());
        entity.setClouds(row.clouds());
        entity.setRain(row.rain());
        entity.setThermalSensation(row.thermalSensation());
        entity.setWind(row.wind());
        entity.setCaptureType(row.captureType());
        entity.setDead(row.dead());
        entity.setSourceName(row.sourceName());
        entity.setSourceReference(row.sourceReference());
        entity.setReviewStatus("OK");
        return entity;
    }

    private static void restoreBackupAliases(
            ConnectionSource source,
            RingLogV3BackupModel model,
            Map<String, Long> eventIds
    ) throws SQLException {
        Dao<EventSourceAliasEntity, Long> dao = dao(source, EventSourceAliasEntity.class);
        for (RingLogV3BackupModel.EventRow event : model.events()) {
            long eventId = requiredId(eventIds, event.stableKey(), "evento");
            for (String alias : new HashSet<>(event.sourceAliases())) {
                QueryBuilder<EventSourceAliasEntity, Long> query = dao.queryBuilder();
                query.where().eq("event_id", eventId).and().eq("source_reference", alias);
                if (dao.queryForFirst(query.prepare()) != null) {
                    continue;
                }
                EventSourceAliasEntity entity = new EventSourceAliasEntity();
                entity.setStableKey(UUID.randomUUID().toString());
                entity.setEventId(eventId);
                entity.setSourceName(aliasSource(alias, event.sourceName()));
                entity.setSourceReference(alias);
                dao.create(entity);
            }
        }
    }

    private static void restoreBackupPhotos(
            ConnectionSource source,
            RingLogV3BackupModel model,
            Map<String, Long> eventIds,
            BackupPhotoStorageService storage,
            MediaPathResolver resolver
    ) throws IOException, SQLException {
        Dao<EventPhotoEntity, Long> dao = dao(source, EventPhotoEntity.class);
        for (RingLogV3BackupModel.PhotoRow row : model.photos()) {
            long eventId = requiredId(eventIds, row.eventStableKey(), "evento");
            EventPhotoEntity entity = first(dao, "stable_key", row.stableKey());
            if (entity != null && entity.getEventId() != eventId) {
                throw conflict("fotografía", row.stableKey());
            }
            boolean fileIsHealthy = entity != null
                    && BackupPhotoStorageService.matches(
                    managedPath(resolver, "event_photo", entity.getFilePath()),
                    row.contentSha256(), row.content().length
            );
            if (fileIsHealthy) {
                continue;
            }
            Path stored = storage.publish(row);
            if (entity == null) {
                entity = new EventPhotoEntity();
                entity.setStableKey(row.stableKey());
                entity.setEventId(eventId);
                entity.setFileName(row.fileName());
                entity.setFilePath(resolver.toEventReference(PhotoArea.NATIVE, stored));
                entity.setMimeType(row.mimeType());
                entity.setSourceName(aliasSource(row.sourceReference(), "RingLog backup"));
                entity.setSourceReference(row.sourceReference());
                entity.setContentSha256(row.contentSha256());
                entity.setContentSize((long) row.content().length);
                dao.create(entity);
                restoreCreatedAt(dao, EventPhotoEntity.TABLE, entity.getId(), row.createdAt());
            } else {
                entity.setFilePath(resolver.toEventReference(PhotoArea.NATIVE, stored));
                entity.setContentSha256(row.contentSha256());
                entity.setContentSize((long) row.content().length);
                dao.update(entity);
            }
        }
    }

    private static void persistBackupWarnings(
            ConnectionSource source,
            long batchId,
            RingLogV3BackupModel model
    ) throws SQLException {
        Dao<MigrationWarningEntity, Long> dao = dao(source, MigrationWarningEntity.class);
        for (RingLogV3BackupModel.WarningRow row : model.warnings()) {
            MigrationWarningEntity entity = new MigrationWarningEntity();
            entity.setStableKey(UUID.randomUUID().toString());
            entity.setImportBatchId(batchId);
            entity.setSeverity(row.severity());
            entity.setSource(row.source());
            entity.setReference(row.reference());
            entity.setMessage(row.message());
            dao.create(entity);
        }
    }

    private static <T> void restoreTimestamps(
            Dao<T, Long> dao,
            String table,
            long id,
            String createdAt,
            String updatedAt
    ) throws SQLException {
        if (updatedAt == null) {
            dao.updateRaw(
                    "UPDATE " + table + " SET created_at = ?, updated_at = NULL WHERE id = ?",
                    createdAt, Long.toString(id)
            );
            // The generic updated_at trigger observes NULL -> NULL as unchanged. A second
            // parameterized update restores the exported NULL after that trigger fires.
            dao.updateRaw(
                    "UPDATE " + table + " SET updated_at = NULL WHERE id = ?",
                    Long.toString(id)
            );
        } else {
            dao.updateRaw(
                    "UPDATE " + table + " SET created_at = ?, updated_at = ? WHERE id = ?",
                    createdAt, updatedAt, Long.toString(id)
            );
        }
    }

    private static <T> void restoreCreatedAt(
            Dao<T, Long> dao,
            String table,
            long id,
            String createdAt
    ) throws SQLException {
        dao.updateRaw(
                "UPDATE " + table + " SET created_at = ? WHERE id = ?",
                createdAt, Long.toString(id)
        );
    }

    private static Path managedPath(
            MediaPathResolver resolver,
            String table,
            String value
    ) {
        try {
            if (value == null) {
                return null;
            }
            return "legacy_unassigned_photo".equals(table)
                    ? resolver.resolveUnassignedPhoto(value)
                    : resolver.resolveEventPhoto(value);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static Map<String, Long> mergeSpecies(
            ConnectionSource source,
            LegacyImportModel model
    ) throws SQLException {
        Dao<SpeciesEntity, Long> dao = dao(source, SpeciesEntity.class);
        Map<String, Long> result = new HashMap<>();
        for (LegacyImportModel.SpeciesRow row : model.species()) {
            SpeciesEntity byCode = row.code() == null ? null : first(dao, "code", row.code());
            SpeciesEntity byName = first(dao, "scientific_name", row.scientificName());
            if (byCode != null && byName != null && byCode.id() != byName.id()) {
                throw conflict("especie", row.speciesKey());
            }
            SpeciesEntity entity = byCode != null ? byCode : byName;
            if (entity == null) {
                entity = new SpeciesEntity(row.code(), row.scientificName(), row.commonName());
                entity.setActive(row.active());
                dao.create(entity);
            } else if (!sameSpecies(entity, row)) {
                throw conflict("especie", row.speciesKey());
            }
            result.put(row.speciesKey(), entity.id());
        }
        return result;
    }

    private static Map<String, Long> mergeBirds(
            ConnectionSource source,
            LegacyImportModel model,
            Map<String, Long> speciesIds
    ) throws SQLException {
        Dao<BirdEntity, Long> dao = dao(source, BirdEntity.class);
        Map<String, Long> result = new HashMap<>();
        for (LegacyImportModel.BirdRow row : model.birds()) {
            long speciesId = requiredId(speciesIds, row.speciesKey(), "especie");
            BirdEntity entity = first(dao, "ring_number", row.ringNumber());
            if (entity == null) {
                entity = new BirdEntity(row.ringNumber(), speciesId);
                dao.create(entity);
            } else if (entity.getSpeciesId() != speciesId) {
                throw conflict("ave", row.ringNumber());
            }
            result.put(row.ringNumber(), entity.id());
        }
        return result;
    }

    private static Map<String, Long> mergePlaces(
            ConnectionSource source,
            LegacyImportModel model
    ) throws SQLException {
        Dao<PlaceEntity, Long> dao = dao(source, PlaceEntity.class);
        Map<String, Long> result = new HashMap<>();
        for (LegacyImportModel.PlaceRow row : model.places()) {
            QueryBuilder<PlaceEntity, Long> query = dao.queryBuilder();
            if (row.locality() == null) {
                query.where().eq("name", row.name()).and().isNull("locality");
            } else {
                query.where().eq("name", row.name()).and().eq("locality", row.locality());
            }
            PlaceEntity entity = dao.queryForFirst(query.prepare());
            if (entity == null) {
                entity = new PlaceEntity(
                        row.name(), row.locality(), decimal(row.latitude()),
                        decimal(row.longitude()), row.notes(), row.favorite(), row.defaultPlace()
                );
                entity.setActive(row.active());
                dao.create(entity);
            } else if (!samePlace(entity, row)) {
                throw conflict("lugar", row.placeKey());
            }
            result.put(row.placeKey(), entity.id());
        }
        return result;
    }

    private static Map<String, Long> mergeEvents(
            ConnectionSource source,
            LegacyImportModel model,
            Map<String, Long> birdIds,
            Map<String, Long> placeIds
    ) throws SQLException {
        Dao<BirdEventEntity, Long> dao = dao(source, BirdEventEntity.class);
        Map<String, Long> result = new HashMap<>();
        for (LegacyImportModel.EventRow row : model.events()) {
            long birdId = requiredId(birdIds, row.ringNumber(), "ave");
            Long placeId = row.placeKey() == null
                    ? null : requiredId(placeIds, row.placeKey(), "lugar");
            BirdEventEntity entity = first(dao, "migration_key", row.eventKey());
            if (entity == null) {
                entity = eventEntity(row, birdId, placeId);
                dao.create(entity);
            } else if (!sameEvent(entity, row, birdId, placeId)) {
                throw conflict("evento", row.eventKey());
            }
            result.put(row.eventKey(), entity.id());
        }
        return result;
    }

    private static BirdEventEntity eventEntity(
            LegacyImportModel.EventRow row,
            long birdId,
            Long placeId
    ) {
        BirdEventEntity entity = new BirdEventEntity();
        entity.setMigrationKey(row.eventKey());
        entity.setBirdId(birdId);
        entity.setEventType(row.eventType());
        entity.setEventDate(row.eventDate().toString());
        entity.setEventTime(string(row.eventTime()));
        entity.setPlaceId(placeId);
        entity.setLocationText(row.locationText());
        entity.setSexCode(row.sexCode());
        entity.setAgeEuringCode(row.ageEuringCode());
        entity.setFatScore(row.fatScore());
        entity.setMuscleScore(row.muscleScore());
        entity.setRingerInitials(row.ringerInitials());
        entity.setStatus(row.status());
        entity.setReproductiveStatus(row.reproductiveStatus());
        entity.setMoultIntensity(row.moultIntensity());
        entity.setMoultExtension(row.moultExtension());
        entity.setBirdCondition(row.birdCondition());
        entity.setReturnStatus(row.returnStatus());
        entity.setWing(decimal(row.wing()));
        entity.setP3(decimal(row.p3()));
        entity.setTorso(decimal(row.torso()));
        entity.setWeight(decimal(row.weight()));
        entity.setObservations(row.observations());
        entity.setClouds(row.clouds());
        entity.setRain(row.rain());
        entity.setThermalSensation(decimalString(row.thermalSensation()));
        entity.setWind(row.wind());
        entity.setCaptureType(row.captureType());
        entity.setDead(row.dead());
        entity.setSourceName(row.sourceName());
        entity.setSourceReference(row.sourceReference());
        entity.setReviewStatus(row.reviewStatus());
        entity.setReviewNote(row.reviewNote());
        return entity;
    }

    private static void persistMetadata(
            ConnectionSource source,
            long batchId,
            Map<String, String> metadata
    ) throws SQLException {
        Dao<ImportMetadataEntity, Long> dao = dao(source, ImportMetadataEntity.class);
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            ImportMetadataEntity entity = new ImportMetadataEntity();
            entity.setImportBatchId(batchId);
            entity.setMetadataKey(entry.getKey());
            entity.setMetadataValue(entry.getValue());
            dao.create(entity);
        }
    }

    private static void persistAliases(
            ConnectionSource source,
            LegacyImportModel model,
            Map<String, Long> eventIds
    ) throws SQLException {
        Dao<EventSourceAliasEntity, Long> dao = dao(source, EventSourceAliasEntity.class);
        for (LegacyImportModel.EventRow event : model.events()) {
            Set<String> seen = new HashSet<>();
            for (String alias : event.aliases()) {
                if (!seen.add(alias)) {
                    continue;
                }
                EventSourceAliasEntity entity = new EventSourceAliasEntity();
                entity.setStableKey(UUID.randomUUID().toString());
                entity.setEventId(requiredId(eventIds, event.eventKey(), "evento"));
                entity.setSourceName(aliasSource(alias, event.sourceName()));
                entity.setSourceReference(alias);
                dao.create(entity);
            }
        }
    }

    private static void persistPhotos(
            ConnectionSource source,
            LegacyImportModel model,
            Map<String, Long> eventIds,
            PhotoStorageService storage,
            MediaPathResolver resolver
    ) throws IOException, SQLException {
        Dao<EventPhotoEntity, Long> dao = dao(source, EventPhotoEntity.class);
        for (LegacyImportModel.PhotoRow row : model.photos()) {
            long eventId = requiredId(eventIds, row.eventKey(), "evento");
            Path stored = storage.publish(row);
            EventPhotoEntity entity = new EventPhotoEntity();
            entity.setEventId(eventId);
            entity.setFileName(row.fileName());
            entity.setFilePath(resolver.toEventReference(PhotoArea.EVENTS, stored));
            entity.setMimeType(row.mimeType());
            entity.setSourceName(aliasSource(row.sourceReference(), "LEGACY"));
            entity.setSourceReference(row.sourceReference());
            entity.setContentSha256(row.contentSha256());
            entity.setContentSize(row.contentSize());
            dao.create(entity);
        }
    }

    private static void persistSourceRecords(
            ConnectionSource source,
            long batchId,
            LegacyImportModel model
    ) throws SQLException {
        Dao<LegacySourceRecordEntity, Long> dao = dao(source, LegacySourceRecordEntity.class);
        for (LegacyImportModel.SourceRecordRow row : model.sourceRecords()) {
            LegacySourceRecordEntity entity = new LegacySourceRecordEntity();
            entity.setStableKey(UUID.randomUUID().toString());
            entity.setImportBatchId(batchId);
            entity.setSourceName(row.sourceName());
            entity.setSourceSection(row.sourceSection());
            entity.setSourceReference(row.sourceReference());
            entity.setRingNumber(row.ringNumber());
            entity.setRawPayload(row.rawPayload());
            dao.create(entity);
        }
    }

    private static void persistUnassignedPhotos(
            ConnectionSource source,
            long batchId,
            LegacyImportModel model,
            PhotoStorageService storage,
            MediaPathResolver resolver,
            String importedAt
    ) throws IOException, SQLException {
        Dao<LegacyUnassignedPhotoEntity, Long> dao = dao(
                source, LegacyUnassignedPhotoEntity.class
        );
        for (LegacyImportModel.UnassignedPhotoRow row : model.unassignedPhotos()) {
            Path stored = storage.publish(row);
            LegacyUnassignedPhotoEntity entity = new LegacyUnassignedPhotoEntity();
            entity.setStableKey(UUID.randomUUID().toString());
            entity.setImportBatchId(batchId);
            entity.setFileName(row.fileName());
            entity.setFilePath(resolver.toUnassignedReference(stored));
            entity.setMimeType(row.mimeType());
            entity.setContentSha256(row.contentSha256());
            entity.setContentSize(row.contentSize());
            entity.setWidth(row.width());
            entity.setHeight(row.height());
            entity.setSourceReference(row.sourceReference());
            entity.setCreatedAt(importedAt);
            dao.create(entity);
        }
    }

    private static void persistAudit(
            ConnectionSource source,
            long batchId,
            LegacyImportModel model
    ) throws SQLException {
        Dao<MigrationAuditEntity, Long> dao = dao(source, MigrationAuditEntity.class);
        for (LegacyImportModel.AuditRow row : model.audit()) {
            MigrationAuditEntity entity = new MigrationAuditEntity();
            entity.setStableKey(UUID.randomUUID().toString());
            entity.setImportBatchId(batchId);
            entity.setSourceName(row.sourceName());
            entity.setSourceSection(row.sourceSection());
            entity.setSourceReference(row.sourceReference());
            entity.setSourceField(row.sourceField());
            entity.setOriginalValue(row.originalValue());
            entity.setOriginalDisplay(row.originalDisplay());
            entity.setDestination(row.destination());
            entity.setNormalizedValue(row.normalizedValue());
            entity.setStatus(row.status());
            entity.setNote(row.note());
            dao.create(entity);
        }
    }

    private static void persistWarnings(
            ConnectionSource source,
            long batchId,
            LegacyImportModel model
    ) throws SQLException {
        Dao<MigrationWarningEntity, Long> dao = dao(source, MigrationWarningEntity.class);
        for (LegacyImportModel.WarningRow row : model.warnings()) {
            MigrationWarningEntity entity = new MigrationWarningEntity();
            entity.setStableKey(UUID.randomUUID().toString());
            entity.setImportBatchId(batchId);
            entity.setSeverity(row.severity());
            entity.setSource(row.source());
            entity.setReference(row.reference());
            entity.setMessage(row.message());
            dao.create(entity);
        }
    }

    private static void persistConflicts(
            ConnectionSource source,
            long batchId,
            LegacyImportModel model,
            Map<String, Long> eventIds,
            String importedAt
    ) throws SQLException {
        Dao<MigrationConflictEntity, Long> dao = dao(source, MigrationConflictEntity.class);
        for (LegacyImportModel.ConflictRow row : model.conflicts()) {
            if (first(dao, "conflict_key", row.conflictKey()) != null) {
                throw conflict("conflicto", row.conflictKey());
            }
            boolean resolved = "RESOLVED".equals(row.status());
            MigrationConflictEntity entity = new MigrationConflictEntity();
            entity.setStableKey(UUID.randomUUID().toString());
            entity.setConflictKey(row.conflictKey());
            entity.setImportBatchId(batchId);
            entity.setEventId(requiredId(eventIds, row.canonicalEventKey(), "evento"));
            entity.setRingNumber(row.ringNumber());
            entity.setConflictType(row.conflictType());
            entity.setEventType(row.eventType());
            entity.setFieldName(row.fieldName());
            entity.setCanonicalSourceReference(row.canonicalSourceReference());
            entity.setCanonicalValue(row.canonicalValue());
            entity.setAlternativeSourceReference(row.alternativeSourceReference());
            entity.setAlternativeValue(row.alternativeValue());
            entity.setCanonicalSnapshot(row.canonicalEventSnapshot());
            entity.setAlternativeSnapshot(row.alternativeEventSnapshot());
            entity.setOriginalNote(row.note());
            entity.setStatus(row.status());
            if (resolved) {
                entity.setResolutionType("CONFIRMED_SOURCE_TYPO".equals(row.conflictType())
                        ? "CONFIRMED_TYPO" : "LEGACY_RESOLUTION");
                entity.setResolutionValue(row.resolutionValue());
                entity.setResolvedAt(importedAt);
                entity.setResolutionNotes("Resolución conservada del migrador legacy v5.2");
            }
            dao.create(entity);
        }
    }

    private static void recomputeImportedReviews(
            ConnectionSource source,
            java.util.Collection<Long> eventIds
    ) throws SQLException {
        Dao<BirdEventEntity, Long> eventDao = dao(source, BirdEventEntity.class);
        Dao<MigrationConflictEntity, Long> conflictDao = dao(
                source, MigrationConflictEntity.class
        );
        for (long eventId : eventIds) {
            QueryBuilder<MigrationConflictEntity, Long> query = conflictDao.queryBuilder();
            query.where().eq("event_id", eventId).and().eq("status", "PENDING_REVIEW");
            query.setCountOf(true);
            BirdEventEntity event = eventDao.queryForId(eventId);
            event.setReviewStatus(conflictDao.countOf(query.prepare()) == 0 ? "OK" : "REVIEW");
            eventDao.update(event);
        }
    }

    private static boolean sameSpecies(
            SpeciesEntity entity,
            LegacyImportModel.SpeciesRow row
    ) {
        return Objects.equals(entity.getCode(), row.code())
                && Objects.equals(entity.getScientificName(), row.scientificName())
                && Objects.equals(entity.getCommonName(), row.commonName())
                && entity.isActive() == row.active();
    }

    private static boolean samePlace(PlaceEntity entity, LegacyImportModel.PlaceRow row) {
        return Objects.equals(entity.getName(), row.name())
                && Objects.equals(entity.getLocality(), row.locality())
                && Objects.equals(entity.getLatitude(), decimal(row.latitude()))
                && Objects.equals(entity.getLongitude(), decimal(row.longitude()))
                && Objects.equals(entity.getNotes(), row.notes())
                && entity.isFavorite() == row.favorite()
                && entity.isDefaultPlace() == row.defaultPlace()
                && entity.isActive() == row.active();
    }

    private static boolean sameEvent(
            BirdEventEntity entity,
            LegacyImportModel.EventRow row,
            long birdId,
            Long placeId
    ) {
        return entity.getBirdId() == birdId
                && Objects.equals(entity.getEventType(), row.eventType())
                && Objects.equals(entity.getEventDate(), row.eventDate().toString())
                && Objects.equals(entity.getEventTime(), string(row.eventTime()))
                && Objects.equals(entity.getPlaceId(), placeId)
                && Objects.equals(entity.getLocationText(), row.locationText())
                && Objects.equals(entity.getSexCode(), row.sexCode())
                && Objects.equals(entity.getAgeEuringCode(), row.ageEuringCode())
                && Objects.equals(entity.getFatScore(), row.fatScore())
                && Objects.equals(entity.getMuscleScore(), row.muscleScore())
                && Objects.equals(entity.getRingerInitials(), row.ringerInitials())
                && Objects.equals(entity.getStatus(), row.status())
                && Objects.equals(entity.getReproductiveStatus(), row.reproductiveStatus())
                && Objects.equals(entity.getMoultIntensity(), row.moultIntensity())
                && Objects.equals(entity.getMoultExtension(), row.moultExtension())
                && Objects.equals(entity.getBirdCondition(), row.birdCondition())
                && Objects.equals(entity.getReturnStatus(), row.returnStatus())
                && Objects.equals(entity.getWing(), decimal(row.wing()))
                && Objects.equals(entity.getP3(), decimal(row.p3()))
                && Objects.equals(entity.getTorso(), decimal(row.torso()))
                && Objects.equals(entity.getWeight(), decimal(row.weight()))
                && Objects.equals(entity.getObservations(), row.observations())
                && Objects.equals(entity.getClouds(), row.clouds())
                && Objects.equals(entity.getRain(), row.rain())
                && Objects.equals(entity.getThermalSensation(), decimalString(row.thermalSensation()))
                && Objects.equals(entity.getWind(), row.wind())
                && Objects.equals(entity.getCaptureType(), row.captureType())
                && entity.isDead() == row.dead()
                && Objects.equals(entity.getSourceName(), row.sourceName())
                && Objects.equals(entity.getSourceReference(), row.sourceReference());
    }

    private static SQLException conflict(String kind, String key) {
        return new SQLException(
                "La " + kind + " " + key + " ya existe con contenido diferente."
        );
    }

    private static long requiredId(Map<String, Long> values, String key, String kind)
            throws SQLException {
        Long value = values.get(key);
        if (value == null) {
            throw new SQLException("No se encontró la " + kind + " referenciada: " + key);
        }
        return value;
    }

    private static String aliasSource(String reference, String fallback) {
        if (reference != null) {
            int colon = reference.indexOf(':');
            if (colon > 0) {
                return reference.substring(0, colon);
            }
        }
        return fallback == null || fallback.isBlank() ? "LEGACY" : fallback;
    }

    private static Double decimal(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static String decimalString(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static Map<String, String> linkedValues() {
        return new java.util.LinkedHashMap<>();
    }

    private static void copy(
            Map<String, String> destination,
            NativeRow source,
            String... columns
    ) {
        for (String column : columns) {
            destination.put(column, source.value(column));
        }
    }

    private static void insertOrVerify(
            Dao<ImportBatchEntity, Long> anchor,
            String table,
            String whereClause,
            String[] whereArguments,
            Map<String, String> values
    ) throws SQLException {
        String columns = String.join(", ", values.keySet());
        String sql = "SELECT " + columns + " FROM " + table + " WHERE " + whereClause;
        List<String[]> existing;
        try (GenericRawResults<String[]> raw = anchor.queryRaw(sql, whereArguments)) {
            existing = raw.getResults();
        } catch (Exception exception) {
            if (exception instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new SQLException("No se pudo cerrar la consulta nativa.", exception);
        }
        if (existing.size() > 1) {
            throw new SQLException("La identidad nativa no es única en " + table + ".");
        }
        if (!existing.isEmpty()) {
            String[] row = existing.get(0);
            int index = 0;
            for (Map.Entry<String, String> expected : values.entrySet()) {
                if (!Objects.equals(expected.getValue(), row[index++])) {
                    throw new SQLException(
                            "La fila " + table + " ya existe con contenido diferente ("
                                    + expected.getKey() + ")."
                    );
                }
            }
            return;
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(
                values.size(), "?"
        ));
        anchor.executeRaw(
                "INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")",
                values.values().toArray(String[]::new)
        );
    }

    private static void verifyExisting(
            Dao<ImportBatchEntity, Long> anchor,
            String table,
            String whereClause,
            String[] whereArguments,
            Map<String, String> values
    ) throws SQLException {
        String columns = String.join(", ", values.keySet());
        List<String[]> rows;
        try (GenericRawResults<String[]> raw = anchor.queryRaw(
                "SELECT " + columns + " FROM " + table + " WHERE " + whereClause,
                whereArguments
        )) {
            rows = raw.getResults();
        } catch (SQLException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SQLException("No se pudo cerrar la verificación nativa.", exception);
        }
        if (rows.size() != 1) {
            throw new SQLException("No se encontró una única fila nativa en " + table + ".");
        }
        String[] actual = rows.get(0);
        int index = 0;
        for (Map.Entry<String, String> expected : values.entrySet()) {
            if (!Objects.equals(expected.getValue(), actual[index++])) {
                throw new SQLException(
                        "La fila " + table + " diverge en " + expected.getKey() + "."
                );
            }
        }
    }

    private static Long queryOptionalId(
            Dao<ImportBatchEntity, Long> anchor,
            String table,
            String stableKey
    ) throws SQLException {
        try (GenericRawResults<String[]> raw = anchor.queryRaw(
                "SELECT id FROM " + table + " WHERE stable_key = ?", stableKey
        )) {
            List<String[]> rows = raw.getResults();
            if (rows.isEmpty()) {
                return null;
            }
            if (rows.size() != 1) {
                throw new SQLException("La identidad está repetida en " + table + ".");
            }
            return Long.valueOf(rows.get(0)[0]);
        } catch (SQLException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SQLException("No se pudo cerrar la consulta de identidad.", exception);
        }
    }

    private static String querySingleValue(
            Dao<ImportBatchEntity, Long> anchor,
            String sql,
            String... arguments
    ) throws SQLException {
        try (GenericRawResults<String[]> raw = anchor.queryRaw(sql, arguments)) {
            List<String[]> rows = raw.getResults();
            if (rows.size() != 1) {
                throw new SQLException("La consulta nativa no devolvió una única fila.");
            }
            return rows.get(0)[0];
        } catch (SQLException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SQLException("No se pudo cerrar la consulta nativa.", exception);
        }
    }

    private static long queryId(
            Dao<ImportBatchEntity, Long> anchor,
            String table,
            String stableKey
    ) throws SQLException {
        try (GenericRawResults<String[]> raw = anchor.queryRaw(
                "SELECT id FROM " + table + " WHERE stable_key = ?", stableKey
        )) {
            List<String[]> rows = raw.getResults();
            if (rows.size() != 1) {
                throw new SQLException("No se pudo resolver la identidad en " + table + ".");
            }
            return Long.parseLong(rows.get(0)[0]);
        } catch (SQLException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SQLException("No se pudo cerrar la consulta de identidad.", exception);
        }
    }

    private static <T, ID> Dao<T, ID> dao(ConnectionSource source, Class<T> type)
            throws SQLException {
        return DaoManager.createDao(source, type);
    }

    private static <T, ID> T first(Dao<T, ID> dao, String column, Object value)
            throws SQLException {
        QueryBuilder<T, ID> query = dao.queryBuilder();
        query.where().eq(column, value);
        return dao.queryForFirst(query.prepare());
    }
}
