package com.adelylria.ringlog.importexport;

import java.io.IOException;
import java.nio.file.Path;

import com.adelylria.ringlog.importexport.legacy.LegacyImportModel;
import com.adelylria.ringlog.importexport.legacy.LegacyV5WorkbookReader;
import com.adelylria.ringlog.importexport.compatibility.RingLogV3BackupModel;
import com.adelylria.ringlog.importexport.compatibility.RingLogV3BackupReader;
import com.adelylria.ringlog.importexport.nativeformat.NativeExportModel;
import com.adelylria.ringlog.importexport.nativeformat.RingLogExportReader;

/** Detects and fully validates an import before any persistent state is changed. */
public final class ImportCoordinator {

    public ImportPlan analyze(Path path) throws ImportValidationException {
        ImportSource source = ImportSource.open(path);
        try {
            WorkbookProfile profile = new WorkbookFormatDetector().detect(source);
            ImportFingerprintService fingerprints = new ImportFingerprintService();
            if (profile.format() == ImportFormat.LEGACY_V5) {
                LegacyImportModel model = new LegacyV5WorkbookReader().read(source);
                return new ImportPlan(
                        source, profile, model, null, null, fingerprints.fingerprint(model)
                );
            }
            if (profile.format() == ImportFormat.RINGLOG_BACKUP_V3) {
                RingLogV3BackupModel model = new RingLogV3BackupReader().read(source);
                return new ImportPlan(
                        source, profile, null, model, null, fingerprints.fingerprint(model)
                );
            }
            if (profile.format() == ImportFormat.RINGLOG_EXPORT_V1) {
                NativeExportModel model = new RingLogExportReader().read(source);
                return new ImportPlan(
                        source, profile, null, null, model, fingerprints.fingerprint(model)
                );
            }
            throw new ImportValidationException(
                    "Este formato se incorporará en su fase específica: " + profile.format()
            );
        } catch (ImportValidationException | RuntimeException exception) {
            closeAfterFailure(source, exception);
            throw exception;
        }
    }

    private static void closeAfterFailure(ImportSource source, Exception original) {
        try {
            source.close();
        } catch (IOException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }
}
