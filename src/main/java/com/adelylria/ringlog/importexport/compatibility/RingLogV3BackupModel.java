package com.adelylria.ringlog.importexport.compatibility;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable staging model for the already released self-contained RingLog backup v3. */
public record RingLogV3BackupModel(
        Map<String, String> metadata,
        List<SpeciesRow> species,
        List<BirdRow> birds,
        List<PlaceRow> places,
        List<EventRow> events,
        List<PhotoRow> photos,
        List<WarningRow> warnings
) {

    public RingLogV3BackupModel {
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        species = List.copyOf(Objects.requireNonNull(species, "species"));
        birds = List.copyOf(Objects.requireNonNull(birds, "birds"));
        places = List.copyOf(Objects.requireNonNull(places, "places"));
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        photos = List.copyOf(Objects.requireNonNull(photos, "photos"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    public record SpeciesRow(
            String stableKey, String code, String scientificName, String commonName,
            boolean active, String createdAt, String updatedAt
    ) {
    }

    public record BirdRow(
            String ringNumber, String speciesStableKey, String createdAt, String updatedAt
    ) {
    }

    public record PlaceRow(
            String stableKey, String name, String locality, Double latitude, Double longitude,
            String notes, boolean favorite, boolean defaultPlace, boolean active,
            String createdAt, String updatedAt
    ) {
    }

    public record EventRow(
            String stableKey,
            String ringNumber,
            String eventType,
            String eventDate,
            String eventTime,
            String placeStableKey,
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
            Double wing,
            Double p3,
            Double torso,
            Double weight,
            String observations,
            String clouds,
            String rain,
            String thermalSensation,
            String wind,
            String captureType,
            boolean dead,
            String sourceName,
            String sourceReference,
            List<String> sourceAliases,
            String createdAt,
            String updatedAt
    ) {
        public EventRow {
            sourceAliases = List.copyOf(Objects.requireNonNull(sourceAliases, "sourceAliases"));
        }
    }

    public record PhotoRow(
            String eventStableKey,
            String fileName,
            String originalPath,
            String mimeType,
            String sourceReference,
            String stableKey,
            String contentSha256,
            byte[] content,
            String createdAt
    ) {
        public PhotoRow {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    public record WarningRow(String severity, String source, String reference, String message) {
    }
}
