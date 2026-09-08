package com.adelylria.ringlog.portable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

final class PortableManifestWriter {

    String write(Path destination, List<PortableFileEntry> entries) throws IOException {
        List<PortableFileEntry> sorted = entries.stream()
                .sorted(Comparator.comparing(PortableFileEntry::path))
                .toList();
        try (JsonGenerator json = new JsonFactory().createGenerator(
                Files.newOutputStream(destination)
        )) {
            json.useDefaultPrettyPrinter();
            json.writeStartObject();
            json.writeStringField("format", "RingLog Portable Manifest");
            json.writeNumberField("formatVersion", 1);
            json.writeArrayFieldStart("files");
            for (PortableFileEntry entry : sorted) {
                json.writeStartObject();
                json.writeStringField("path", entry.path());
                json.writeStringField("kind", entry.kind());
                json.writeNumberField("size", entry.size());
                json.writeStringField("sha256", entry.sha256());
                json.writeEndObject();
            }
            json.writeEndArray();
            json.writeEndObject();
        }
        return PortableHashing.sha256(destination);
    }
}
