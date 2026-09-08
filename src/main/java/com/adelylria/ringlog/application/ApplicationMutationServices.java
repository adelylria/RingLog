package com.adelylria.ringlog.application;

import com.adelylria.ringlog.importexport.service.ConflictResolutionService;
import com.adelylria.ringlog.ui.importexport.ImportExportController;

/** Services that are deliberately absent from a portable read-only context. */
public record ApplicationMutationServices(
        ImportExportController importExport,
        ConflictResolutionService conflictResolution
) {
}
