package com.adelylria.ringlog.ui.importexport;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import com.adelylria.ringlog.importexport.ImportFormat;
import com.adelylria.ringlog.importexport.ImportPlan;

/** User-facing summary that retains the already validated import plan. */
public final class ImportAnalysis implements AutoCloseable {

    private final Path file;
    private final ImportPlan plan;
    private final int speciesCount;
    private final int birdsCount;
    private final int placesCount;
    private final int eventsCount;
    private final int photosCount;
    private final int warningCount;
    private final int pendingConflictCount;

    ImportAnalysis(
            Path file,
            ImportPlan plan,
            int speciesCount,
            int birdsCount,
            int placesCount,
            int eventsCount,
            int photosCount,
            int warningCount,
            int pendingConflictCount
    ) {
        this.file = Objects.requireNonNull(file, "file");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.speciesCount = speciesCount;
        this.birdsCount = birdsCount;
        this.placesCount = placesCount;
        this.eventsCount = eventsCount;
        this.photosCount = photosCount;
        this.warningCount = warningCount;
        this.pendingConflictCount = pendingConflictCount;
    }

    public Path file() {
        return file;
    }

    public ImportFormat format() {
        return plan.profile().format();
    }

    public String formatName() {
        return switch (format()) {
            case LEGACY_V5 -> "Migración legacy v5/v5.2";
            case RINGLOG_BACKUP_V3 -> "Copia de RingLog v3";
            case RINGLOG_EXPORT_V1 -> "RingLog Export v1";
        };
    }

    public int speciesCount() {
        return speciesCount;
    }

    public int birdsCount() {
        return birdsCount;
    }

    public int placesCount() {
        return placesCount;
    }

    public int eventsCount() {
        return eventsCount;
    }

    public int photosCount() {
        return photosCount;
    }

    public int warningCount() {
        return warningCount;
    }

    public int pendingConflictCount() {
        return pendingConflictCount;
    }

    public boolean requiresConflictReview() {
        return plan.requiresConflictReview();
    }

    ImportPlan plan() {
        return plan;
    }

    @Override
    public void close() throws IOException {
        plan.close();
    }
}
