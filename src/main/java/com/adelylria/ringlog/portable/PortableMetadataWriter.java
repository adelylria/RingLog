package com.adelylria.ringlog.portable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import com.adelylria.ringlog.database.Database;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

final class PortableMetadataWriter {

    void write(
            Path destination,
            String applicationVersion,
            Instant createdAt,
            PortableDatasetSummary summary,
            long databaseSize,
            String databaseHash,
            String manifestHash
    ) throws IOException {
        try (JsonGenerator json = new JsonFactory().createGenerator(
                Files.newOutputStream(destination)
        )) {
            json.useDefaultPrettyPrinter();
            json.writeStartObject();
            json.writeStringField("format", "RingLog Portable");
            json.writeNumberField("formatVersion", 1);
            json.writeStringField("mode", "PORTABLE_READ_ONLY");
            json.writeStringField("applicationVersion", applicationVersion);
            json.writeNumberField("schemaVersion", Database.SCHEMA_VERSION);
            json.writeStringField("architecture", "x64");
            json.writeStringField("runtimeVersion", "Zulu 17.0.7");
            json.writeStringField("createdAt", createdAt.toString());
            json.writeObjectFieldStart("counts");
            json.writeNumberField("species", summary.species());
            json.writeNumberField("birds", summary.birds());
            json.writeNumberField("places", summary.places());
            json.writeNumberField("events", summary.events());
            json.writeNumberField("eventPhotos", summary.eventPhotos());
            json.writeNumberField("unassignedPhotos", summary.unassignedPhotos());
            json.writeEndObject();
            json.writeObjectFieldStart("database");
            json.writeStringField("path", "data/ringlog.db");
            json.writeNumberField("size", databaseSize);
            json.writeStringField("sha256", databaseHash);
            json.writeEndObject();
            json.writeObjectFieldStart("manifest");
            json.writeStringField("path", "portable-manifest.json");
            json.writeStringField("sha256", manifestHash);
            json.writeEndObject();
            json.writeEndObject();
        }
    }
}
