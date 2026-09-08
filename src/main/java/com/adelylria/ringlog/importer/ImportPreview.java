package com.adelylria.ringlog.importer;

import java.util.List;

public record ImportPreview(
        int speciesCount,
        int birdsCount,
        int placesCount,
        int eventsCount,
        int photosCount,
        List<ImportWarning> warnings
) {

    public ImportPreview {
        warnings = List.copyOf(warnings);
    }

    public long reviewWarningCount() {
        return warnings.stream().filter(ImportWarning::requiresReview).count();
    }

    public List<ImportWarning> getWarnings() {
        return warnings;
    }
}
