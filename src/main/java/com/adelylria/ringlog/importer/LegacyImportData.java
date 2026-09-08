package com.adelylria.ringlog.importer;

import java.nio.file.Path;
import java.util.List;

record LegacyImportData(
        Path workbook,
        int formatVersion,
        List<SpeciesRow> species,
        List<BirdRow> birds,
        List<PlaceRow> places,
        List<EventRow> events,
        List<PhotoRow> photos,
        List<ImportWarning> warnings
) {

    LegacyImportData {
        species = List.copyOf(species);
        birds = List.copyOf(birds);
        places = List.copyOf(places);
        events = List.copyOf(events);
        photos = List.copyOf(photos);
        warnings = List.copyOf(warnings);
    }
}

record SpeciesRow(
        String key,
        String code,
        String scientificName,
        String commonName,
        boolean active,
        String createdAt,
        String updatedAt
) {
}

record BirdRow(
        String ringNumber,
        String speciesKey,
        String createdAt,
        String updatedAt
) {
}

record PlaceRow(
        String key,
        String name,
        String locality,
        Double latitude,
        Double longitude,
        String notes,
        boolean favorite,
        boolean defaultPlace,
        boolean active,
        String createdAt,
        String updatedAt
) {
}

record EventRow(
        String key,
        String ringNumber,
        String eventType,
        String eventDate,
        String eventTime,
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
    EventRow {
        sourceAliases = List.copyOf(sourceAliases);
    }
}

record PhotoRow(
        String eventKey,
        String fileName,
        String relativePath,
        String mimeType,
        String sourceReference,
        String photoKey,
        String contentHash,
        byte[] embeddedContent,
        String createdAt
) {
    PhotoRow {
        embeddedContent = embeddedContent == null ? null : embeddedContent.clone();
    }

    @Override
    public byte[] embeddedContent() {
        return embeddedContent == null ? null : embeddedContent.clone();
    }
}
