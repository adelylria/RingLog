package com.adelylria.ringlog.ui.importexport;

import java.nio.file.Path;
import java.util.Objects;

import com.adelylria.ringlog.diagnostics.SafeLog;
import com.adelylria.ringlog.application.DataMutationCoordinator;

import com.adelylria.ringlog.importexport.ImportCoordinator;
import com.adelylria.ringlog.importexport.ImportPlan;
import com.adelylria.ringlog.importexport.ImportValidationException;
import com.adelylria.ringlog.importexport.compatibility.RingLogV3BackupModel;
import com.adelylria.ringlog.importexport.legacy.LegacyImportModel;
import com.adelylria.ringlog.importexport.nativeformat.ExportResult;
import com.adelylria.ringlog.importexport.nativeformat.NativeExportModel;
import com.adelylria.ringlog.importexport.nativeformat.RingLogExportException;
import com.adelylria.ringlog.importexport.nativeformat.RingLogExporter;
import com.adelylria.ringlog.importexport.service.ImportExecutionResult;
import com.adelylria.ringlog.importexport.service.ImportTransactionService;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.MediaPathResolver;

/** One shared application boundary for analysis, import and native export. */
public final class ImportExportController {

    private final ImportCoordinator coordinator;
    private final ImportTransactionService transactionService;
    private final RingLogExporter exporter;
    private final DataMutationCoordinator mutationCoordinator;

    public ImportExportController(String databaseFile, Path mediaRoot) {
        this(databaseFile, AppPaths.forDataRoot(mediaRoot));
    }

    public ImportExportController(String databaseFile, AppPaths paths) {
        this(databaseFile, paths, new DataMutationCoordinator());
    }

    public ImportExportController(
            String databaseFile,
            AppPaths paths,
            DataMutationCoordinator mutationCoordinator
    ) {
        Objects.requireNonNull(paths, "paths");
        this.mutationCoordinator = Objects.requireNonNull(
                mutationCoordinator, "mutationCoordinator"
        );
        coordinator = new ImportCoordinator();
        transactionService = new ImportTransactionService(databaseFile, paths);
        exporter = new RingLogExporter(databaseFile, new MediaPathResolver(paths));
    }

    public static ImportExportController applicationDefault() {
        return application(AppPaths.production());
    }

    public static ImportExportController application(AppPaths paths) {
        return new ImportExportController(paths.databasePath().toString(), paths);
    }

    public static ImportExportController application(
            AppPaths paths,
            DataMutationCoordinator mutationCoordinator
    ) {
        return new ImportExportController(
                paths.databasePath().toString(), paths, mutationCoordinator
        );
    }

    public ImportAnalysis analyze(Path file) throws ImportValidationException {
        long started = System.nanoTime();
        SafeLog.operationStarted("import_analysis");
        try {
            ImportPlan plan = coordinator.analyze(file);
            try {
                ImportAnalysis analysis = summarize(file, plan);
                SafeLog.operationCompleted(
                        "import_analysis",
                        elapsedMillis(started),
                        analysis.eventsCount()
                );
                return analysis;
            } catch (RuntimeException exception) {
                try {
                    plan.close();
                } catch (java.io.IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                throw exception;
            }
        } catch (ImportValidationException | RuntimeException failure) {
            SafeLog.failure("import_analysis", failure);
            throw failure;
        }
    }

    public ImportExecutionResult execute(ImportAnalysis analysis)
            throws com.adelylria.ringlog.importexport.ImportExecutionException {
        ImportAnalysis checked = Objects.requireNonNull(analysis, "analysis");
        long started = System.nanoTime();
        SafeLog.operationStarted("import_execution");
        try {
            ImportExecutionResult result;
            try (DataMutationCoordinator.Lease ignored =
                         mutationCoordinator.acquireMutation()) {
                result = transactionService.execute(checked.plan());
            }
            SafeLog.operationCompleted(
                    "import_execution",
                    elapsedMillis(started),
                    checked.eventsCount()
            );
            return result;
        } catch (com.adelylria.ringlog.importexport.ImportExecutionException
                 | RuntimeException failure) {
            SafeLog.failure("import_execution", failure);
            throw failure;
        }
    }

    public ExportResult exportTo(Path destination) throws RingLogExportException {
        long started = System.nanoTime();
        SafeLog.operationStarted("native_export");
        try {
            ExportResult result = exporter.exportTo(destination);
            SafeLog.operationCompleted(
                    "native_export",
                    elapsedMillis(started),
                    result.binaryCount()
            );
            return result;
        } catch (RingLogExportException | RuntimeException failure) {
            SafeLog.failure("native_export", failure);
            throw failure;
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static ImportAnalysis summarize(Path file, ImportPlan plan) {
        if (plan.legacyModel() != null) {
            LegacyImportModel model = plan.legacyModel();
            return new ImportAnalysis(
                    file, plan, model.species().size(), model.birds().size(),
                    model.places().size(), model.events().size(),
                    model.photos().size() + model.unassignedPhotos().size(),
                    model.warnings().size(), pendingLegacy(model)
            );
        }
        if (plan.backupV3Model() != null) {
            RingLogV3BackupModel model = plan.backupV3Model();
            return new ImportAnalysis(
                    file, plan, model.species().size(), model.birds().size(),
                    model.places().size(), model.events().size(), model.photos().size(),
                    model.warnings().size(), 0
            );
        }
        NativeExportModel model = plan.nativeModel();
        return new ImportAnalysis(
                file, plan, model.rows("species").size(), model.rows("birds").size(),
                model.rows("places").size(), model.rows("events").size(),
                model.rows("photos").size() + model.rows("unassigned_photos").size(),
                model.rows("migration_warnings").size(), pendingNative(model)
        );
    }

    private static int pendingLegacy(LegacyImportModel model) {
        return Math.toIntExact(model.conflicts().stream()
                .filter(conflict -> "PENDING_REVIEW".equals(conflict.status()))
                .count());
    }

    private static int pendingNative(NativeExportModel model) {
        return Math.toIntExact(model.conflicts().stream()
                .filter(conflict -> "PENDING_REVIEW".equals(conflict.value("status")))
                .count());
    }
}
