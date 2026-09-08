package com.adelylria.ringlog.importexport.legacy;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable staging representation of every authoritative v5.2 sheet. */
public record LegacyImportModel(
        Map<String, String> metadata,
        List<SpeciesRow> species,
        List<BirdRow> birds,
        List<PlaceRow> places,
        List<EventRow> events,
        List<PhotoRow> photos,
        List<UnassignedPhotoRow> unassignedPhotos,
        List<SourceRecordRow> sourceRecords,
        List<ConflictRow> conflicts,
        List<AuditRow> audit,
        List<WarningRow> warnings
) {

    public LegacyImportModel {
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        species = List.copyOf(Objects.requireNonNull(species, "species"));
        birds = List.copyOf(Objects.requireNonNull(birds, "birds"));
        places = List.copyOf(Objects.requireNonNull(places, "places"));
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        photos = List.copyOf(Objects.requireNonNull(photos, "photos"));
        unassignedPhotos = List.copyOf(
                Objects.requireNonNull(unassignedPhotos, "unassignedPhotos")
        );
        sourceRecords = List.copyOf(Objects.requireNonNull(sourceRecords, "sourceRecords"));
        conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
        audit = List.copyOf(Objects.requireNonNull(audit, "audit"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    public record SpeciesRow(
            String speciesKey,
            String code,
            String scientificName,
            String commonName,
            boolean active
    ) {
    }

    public record BirdRow(String ringNumber, String speciesKey) {
    }

    public record PlaceRow(
            String placeKey,
            String name,
            String locality,
            BigDecimal latitude,
            BigDecimal longitude,
            String notes,
            boolean favorite,
            boolean defaultPlace,
            boolean active
    ) {
    }

    public record EventRow(
            String eventKey,
            String ringNumber,
            String eventType,
            LocalDate eventDate,
            LocalTime eventTime,
            String placeKey,
            String locationText,
            String sexCode,
            String ageEuringCode,
            Integer fatScore,
            Integer muscleScore,
            String ringerInitials,
            String status,
            String reproductiveStatus,
            String moultIntensity,
            String moultExtension,
            String birdCondition,
            String returnStatus,
            BigDecimal wing,
            BigDecimal p3,
            BigDecimal torso,
            BigDecimal weight,
            String observations,
            String clouds,
            String rain,
            BigDecimal thermalSensation,
            String wind,
            String captureType,
            boolean dead,
            String reviewStatus,
            String reviewNote,
            String sourceName,
            String sourceReference,
            String sourceAliases,
            List<String> aliases
    ) {
        public EventRow {
            aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
        }
    }

    public record PhotoRow(
            String eventKey,
            String fileName,
            String filePath,
            String mimeType,
            String sourceReference,
            Path resolvedPath,
            String contentSha256,
            long contentSize
    ) {
    }

    public record UnassignedPhotoRow(
            String fileName,
            String filePath,
            String mimeType,
            String declaredSha256,
            Integer width,
            Integer height,
            String sourceReference,
            Path resolvedPath,
            String contentSha256,
            long contentSize
    ) {
    }

    public record SourceRecordRow(
            String sourceName,
            String sourceSection,
            String sourceReference,
            String ringNumber,
            String rawPayload
    ) {
    }

    public record ConflictRow(
            String conflictKey,
            String ringNumber,
            String conflictType,
            String eventType,
            String fieldName,
            String canonicalEventKey,
            String canonicalSourceReference,
            String canonicalValue,
            String alternativeSourceReference,
            String alternativeValue,
            String canonicalEventSnapshot,
            String alternativeEventSnapshot,
            String status,
            String resolutionValue,
            String note
    ) {
    }

    public record AuditRow(
            String sourceName,
            String sourceSection,
            String sourceReference,
            String sourceField,
            String originalValue,
            String originalDisplay,
            String destination,
            String normalizedValue,
            String status,
            String note
    ) {
    }

    public record WarningRow(
            String severity,
            String source,
            String reference,
            String message
    ) {
    }
}
