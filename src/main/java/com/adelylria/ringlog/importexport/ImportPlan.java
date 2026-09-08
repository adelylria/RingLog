package com.adelylria.ringlog.importexport;

import java.io.IOException;
import java.util.Objects;

import com.adelylria.ringlog.importexport.legacy.LegacyImportModel;
import com.adelylria.ringlog.importexport.compatibility.RingLogV3BackupModel;
import com.adelylria.ringlog.importexport.nativeformat.NativeExportModel;

/** Owns an analyzed source until the caller executes or discards the import. */
public final class ImportPlan implements AutoCloseable {

    private final ImportSource source;
    private final WorkbookProfile profile;
    private final LegacyImportModel legacyModel;
    private final RingLogV3BackupModel backupV3Model;
    private final NativeExportModel nativeModel;
    private final String fingerprint;

    ImportPlan(
            ImportSource source,
            WorkbookProfile profile,
            LegacyImportModel legacyModel,
            RingLogV3BackupModel backupV3Model,
            NativeExportModel nativeModel,
            String fingerprint
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.legacyModel = legacyModel;
        this.backupV3Model = backupV3Model;
        this.nativeModel = nativeModel;
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        int models = (legacyModel == null ? 0 : 1)
                + (backupV3Model == null ? 0 : 1)
                + (nativeModel == null ? 0 : 1);
        if (models != 1) {
            throw new IllegalArgumentException("El plan debe contener exactamente un modelo.");
        }
    }

    public ImportSource source() {
        return source;
    }

    public WorkbookProfile profile() {
        return profile;
    }

    public LegacyImportModel legacyModel() {
        return legacyModel;
    }

    public RingLogV3BackupModel backupV3Model() {
        return backupV3Model;
    }

    public NativeExportModel nativeModel() {
        return nativeModel;
    }

    public boolean requiresConflictReview() {
        return legacyModel != null && legacyModel.conflicts().stream()
                .anyMatch(conflict -> "PENDING_REVIEW".equals(conflict.status()));
    }

    public String fingerprint() {
        return fingerprint;
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}
