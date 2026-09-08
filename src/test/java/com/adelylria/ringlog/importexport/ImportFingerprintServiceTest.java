package com.adelylria.ringlog.importexport;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.adelylria.ringlog.importexport.legacy.LegacyImportModel;
import com.adelylria.ringlog.importexport.legacy.LegacyImportModel.AuditRow;
import com.adelylria.ringlog.importexport.legacy.LegacyImportModel.BirdRow;
import com.adelylria.ringlog.importexport.legacy.LegacyImportModel.ConflictRow;
import com.adelylria.ringlog.importexport.legacy.LegacyImportModel.EventRow;
import com.adelylria.ringlog.importexport.legacy.LegacyImportModel.PhotoRow;
import com.adelylria.ringlog.importexport.legacy.LegacyImportModel.PlaceRow;
import com.adelylria.ringlog.importexport.legacy.LegacyImportModel.SourceRecordRow;
import com.adelylria.ringlog.importexport.legacy.LegacyImportModel.SpeciesRow;
import com.adelylria.ringlog.importexport.legacy.LegacyImportModel.UnassignedPhotoRow;
import com.adelylria.ringlog.importexport.legacy.LegacyImportModel.WarningRow;

/** Determinism and content-sensitivity contract for legacy import idempotency. */
public final class ImportFingerprintServiceTest {

    private ImportFingerprintServiceTest() {
    }

    public static void fingerprintIsCanonicalAndIgnoresOnlyVolatileMetadata() {
        ImportFingerprintService service = new ImportFingerprintService();
        LegacyImportModel base = sample(null, "2026-08-26T10:00:00");
        String expected = service.fingerprint(base);

        LegacyImportModel reordered = reorder(base);
        require(expected.equals(service.fingerprint(reordered)),
                "Workbook row and metadata iteration order must not affect the fingerprint");

        LegacyImportModel differentGeneratedAt = sample(null, "2035-01-01T00:00:00");
        require(expected.equals(service.fingerprint(differentGeneratedAt)),
                "generated_at must be excluded from the canonical dataset fingerprint");

        for (String family : List.of(
                "metadata", "species", "birds", "places", "events", "photos",
                "unassigned_photos", "source_records", "migration_conflicts",
                "migration_audit", "migration_warnings"
        )) {
            String changed = service.fingerprint(sample(family, "2026-08-26T10:00:00"));
            require(!expected.equals(changed),
                    "Changing real content in " + family + " must change the fingerprint");
        }

        require(expected.matches("[0-9a-f]{64}"),
                "The import fingerprint must be lowercase hexadecimal SHA-256");
    }

    private static LegacyImportModel reorder(LegacyImportModel model) {
        Map<String, String> metadata = new LinkedHashMap<>();
        List<Map.Entry<String, String>> metadataEntries =
                new ArrayList<>(model.metadata().entrySet());
        Collections.reverse(metadataEntries);
        metadataEntries.forEach(entry -> metadata.put(entry.getKey(), entry.getValue()));
        List<SpeciesRow> species = new ArrayList<>(model.species());
        Collections.reverse(species);
        return new LegacyImportModel(
                metadata,
                species,
                model.birds(),
                model.places(),
                model.events(),
                model.photos(),
                model.unassignedPhotos(),
                model.sourceRecords(),
                model.conflicts(),
                model.audit(),
                model.warnings()
        );
    }

    private static LegacyImportModel sample(String changedFamily, String generatedAt) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("format", "RingLog Import");
        metadata.put("format_version", "5");
        metadata.put("migrator_version", "5.2");
        metadata.put("generated_at", generatedAt);
        metadata.put("canonical_policy_ringing",
                changed("metadata", changedFamily, "ACCESS > EXCEL > DOCX"));

        List<SpeciesRow> species = List.of(
                new SpeciesRow("SP-2", null, "Beta beta", "Beta", true),
                new SpeciesRow("SP-1", "AA", "Alpha alpha",
                        changed("species", changedFamily, "Alpha"), true)
        );
        List<BirdRow> birds = List.of(new BirdRow(
                changed("birds", changedFamily, "V100"), "SP-1"
        ));
        List<PlaceRow> places = List.of(new PlaceRow(
                "PL-1", changed("places", changedFamily, "Els Rafals"), "Pollença",
                new BigDecimal("39.95"), new BigDecimal("3.01"), "  nota  ",
                true, true, true
        ));
        List<EventRow> events = List.of(new EventRow(
                "EV-1", "V100", "RINGING", LocalDate.of(2026, 1, 3),
                LocalTime.of(17, 40), "PL-1", null, "U", "4", 1, 2,
                "AB", null, null, null, null, "Buen estado", null,
                new BigDecimal("73.8"), new BigDecimal("49.6"),
                new BigDecimal("30.1"), new BigDecimal("17.5"),
                changed("events", changedFamily, "observación"), null, "No",
                new BigDecimal("23.8"), "Ninguno", "CAPTURE", false,
                "REVIEW", "pendiente", "Excel", "XLSX:1",
                "ACCESS:1 | XLSX:1", List.of("ACCESS:1", "XLSX:1")
        ));
        List<PhotoRow> photos = List.of(new PhotoRow(
                "EV-1", "ave.jpg", "photos/events/ave.jpg", "image/jpeg", "ACCESS:P:1",
                Path.of("ignored-event-photo"),
                changed("photos", changedFamily,
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                42L
        ));
        List<UnassignedPhotoRow> unassigned = List.of(new UnassignedPhotoRow(
                "lost.jpg", "photos/unassigned/lost.jpg", "image/jpeg",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                100, 80, "ACCESS:PHOTO:9", Path.of("ignored-unassigned-photo"),
                changed("unassigned_photos", changedFamily,
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
                84L
        ));
        List<SourceRecordRow> sourceRecords = List.of(new SourceRecordRow(
                "Access", "CAPTURAS", "ACCESS:1", "V100",
                changed("source_records", changedFamily, "{\n  \"raw\": true\n}")
        ));
        List<ConflictRow> conflicts = List.of(new ConflictRow(
                "CF-1", "V100", "FIELD_CONFLICT", "RINGING", "event_time",
                "EV-1", "XLSX:1", "17:40", "DOCX:1", "17:35",
                "{canonical}", changed("migration_conflicts", changedFamily, "{alternative}"),
                "PENDING_REVIEW", null, "nota original"
        ));
        List<AuditRow> audit = List.of(new AuditRow(
                "Access", "CAPTURAS", "ACCESS:1", "Fecha", "3/1/2026",
                "03/01/2026", "events.event_date", "2026-01-03", "MIGRATED",
                changed("migration_audit", changedFamily, "conservado")
        ));
        List<WarningRow> warnings = List.of(new WarningRow(
                "REVIEW", "Excel", "XLSX:1",
                changed("migration_warnings", changedFamily, "Revisar el campo")
        ));

        return new LegacyImportModel(
                metadata, species, birds, places, events, photos, unassigned,
                sourceRecords, conflicts, audit, warnings
        );
    }

    private static String changed(String family, String selected, String value) {
        return family.equals(selected) ? value + " · cambiado" : value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        fingerprintIsCanonicalAndIgnoresOnlyVolatileMetadata();
        System.out.println("ImportFingerprintServiceTest: PASS");
    }
}
