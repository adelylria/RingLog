package com.adelylria.ringlog.importexport.compatibility;

import com.adelylria.ringlog.importer.LegacyImportException;
import com.adelylria.ringlog.importer.LegacyWorkbookReader;
import com.adelylria.ringlog.importexport.ImportFormat;
import com.adelylria.ringlog.importexport.ImportSource;
import com.adelylria.ringlog.importexport.ImportValidationException;
import com.adelylria.ringlog.importexport.WorkbookFormatDetector;
import com.adelylria.ringlog.importexport.WorkbookProfile;

/** Isolated compatibility adapter; it parses v3 but performs no persistence or inference. */
public final class RingLogV3BackupReader {

    public RingLogV3BackupModel read(ImportSource source) throws ImportValidationException {
        WorkbookProfile profile = new WorkbookFormatDetector().detect(source);
        if (profile.format() != ImportFormat.RINGLOG_BACKUP_V3) {
            throw new ImportValidationException(
                    "El lector de copias v3 recibió un formato diferente."
            );
        }
        try {
            return new LegacyWorkbookReader().readBackupV3(
                    source.workbookPath(), profile.metadata()
            );
        } catch (LegacyImportException exception) {
            throw new ImportValidationException(exception.getMessage(), exception);
        }
    }
}
