package com.adelylria.ringlog.importexport;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.adelylria.ringlog.importexport.legacy.LegacyImportModel;
import com.adelylria.ringlog.importexport.compatibility.RingLogV3BackupModel;
import com.adelylria.ringlog.importexport.nativeformat.NativeExportModel;

/** Computes a deterministic content identity for a fully parsed legacy dataset. */
public final class ImportFingerprintService {

    private static final Set<String> VOLATILE_METADATA = Set.of("generated_at");

    public String fingerprint(LegacyImportModel model) {
        if (model == null) {
            throw new IllegalArgumentException("El dataset legacy no puede ser nulo.");
        }
        MessageDigest digest = sha256();
        addCollection(digest, "metadata",
                model.metadata().entrySet().stream()
                        .filter(entry -> !VOLATILE_METADATA.contains(entry.getKey()))
                        .toList(),
                entry -> row(entry.getKey(), entry.getValue()));
        addCollection(digest, "species", model.species(), value -> row(
                value.speciesKey(), value.code(), value.scientificName(), value.commonName(),
                value.active()
        ));
        addCollection(digest, "birds", model.birds(), value -> row(
                value.ringNumber(), value.speciesKey()
        ));
        addCollection(digest, "places", model.places(), value -> row(
                value.placeKey(), value.name(), value.locality(), value.latitude(),
                value.longitude(), value.notes(), value.favorite(), value.defaultPlace(),
                value.active()
        ));
        addCollection(digest, "events", model.events(), value -> row(
                value.eventKey(), value.ringNumber(), value.eventType(), value.eventDate(),
                value.eventTime(), value.placeKey(), value.locationText(), value.sexCode(),
                value.ageEuringCode(), value.fatScore(), value.muscleScore(),
                value.ringerInitials(), value.status(), value.reproductiveStatus(),
                value.moultIntensity(), value.moultExtension(), value.birdCondition(),
                value.returnStatus(), value.wing(), value.p3(), value.torso(), value.weight(),
                value.observations(), value.clouds(), value.rain(), value.thermalSensation(),
                value.wind(), value.captureType(), value.dead(), value.reviewStatus(),
                value.reviewNote(), value.sourceName(), value.sourceReference(), value.sourceAliases()
        ));
        addCollection(digest, "photos", model.photos(), value -> row(
                value.eventKey(), value.fileName(), value.filePath(), value.mimeType(),
                value.sourceReference(), value.contentSha256(), value.contentSize()
        ));
        addCollection(digest, "unassigned_photos", model.unassignedPhotos(), value -> row(
                value.fileName(), value.filePath(), value.mimeType(), value.declaredSha256(),
                value.width(), value.height(), value.sourceReference(), value.contentSha256(),
                value.contentSize()
        ));
        addCollection(digest, "source_records", model.sourceRecords(), value -> row(
                value.sourceName(), value.sourceSection(), value.sourceReference(),
                value.ringNumber(), value.rawPayload()
        ));
        addCollection(digest, "migration_conflicts", model.conflicts(), value -> row(
                value.conflictKey(), value.ringNumber(), value.conflictType(), value.eventType(),
                value.fieldName(), value.canonicalEventKey(), value.canonicalSourceReference(),
                value.canonicalValue(), value.alternativeSourceReference(),
                value.alternativeValue(), value.canonicalEventSnapshot(),
                value.alternativeEventSnapshot(), value.status(), value.resolutionValue(),
                value.note()
        ));
        addCollection(digest, "migration_audit", model.audit(), value -> row(
                value.sourceName(), value.sourceSection(), value.sourceReference(),
                value.sourceField(), value.originalValue(), value.originalDisplay(),
                value.destination(), value.normalizedValue(), value.status(), value.note()
        ));
        addCollection(digest, "migration_warnings", model.warnings(), value -> row(
                value.severity(), value.source(), value.reference(), value.message()
        ));
        return HexFormat.of().formatHex(digest.digest());
    }

    public String fingerprint(RingLogV3BackupModel model) {
        if (model == null) {
            throw new IllegalArgumentException("La copia v3 no puede ser nula.");
        }
        MessageDigest digest = sha256();
        addCollection(digest, "metadata",
                model.metadata().entrySet().stream()
                        .filter(entry -> !VOLATILE_METADATA.contains(entry.getKey()))
                        .toList(),
                entry -> row(entry.getKey(), entry.getValue()));
        addCollection(digest, "species", model.species(), value -> row(
                value.stableKey(), value.code(), value.scientificName(), value.commonName(),
                value.active(), value.createdAt(), value.updatedAt()
        ));
        addCollection(digest, "birds", model.birds(), value -> row(
                value.ringNumber(), value.speciesStableKey(), value.createdAt(), value.updatedAt()
        ));
        addCollection(digest, "places", model.places(), value -> row(
                value.stableKey(), value.name(), value.locality(), value.latitude(),
                value.longitude(), value.notes(), value.favorite(), value.defaultPlace(),
                value.active(), value.createdAt(), value.updatedAt()
        ));
        addCollection(digest, "events", model.events(), value -> row(
                value.stableKey(), value.ringNumber(), value.eventType(), value.eventDate(),
                value.eventTime(), value.placeStableKey(), value.locationText(), value.sexCode(),
                value.ageEuringCode(), value.fatScore(), value.muscleScore(),
                value.ringerInitials(), value.status(), value.reproductiveStatus(),
                value.moultIntensity(), value.moultExtension(), value.birdCondition(),
                value.returnStatus(), value.wing(), value.p3(), value.torso(), value.weight(),
                value.observations(), value.clouds(), value.rain(), value.thermalSensation(),
                value.wind(), value.captureType(), value.dead(), value.sourceName(),
                value.sourceReference(), String.join("\u0000", value.sourceAliases()),
                value.createdAt(), value.updatedAt()
        ));
        addCollection(digest, "photos", model.photos(), value -> row(
                value.eventStableKey(), value.fileName(), value.originalPath(), value.mimeType(),
                value.sourceReference(), value.stableKey(), value.contentSha256(),
                value.createdAt()
        ));
        addCollection(digest, "warnings", model.warnings(), value -> row(
                value.severity(), value.source(), value.reference(), value.message()
        ));
        return HexFormat.of().formatHex(digest.digest());
    }

    public String fingerprint(NativeExportModel model) {
        if (model == null) {
            throw new IllegalArgumentException("El export nativo no puede ser nulo.");
        }
        MessageDigest digest = sha256();
        addCollection(digest, "metadata",
                model.metadata().entrySet().stream()
                        .filter(entry -> !VOLATILE_METADATA.contains(entry.getKey()))
                        .toList(),
                entry -> row(entry.getKey(), entry.getValue()));
        for (Map.Entry<String, List<NativeExportModel.NativeRow>> sheet
                : model.sheets().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            addCollection(digest, sheet.getKey(), sheet.getValue(), nativeRow -> {
                List<Object> values = new ArrayList<>();
                for (Map.Entry<String, String> value : nativeRow.values().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).toList()) {
                    values.add(value.getKey());
                    values.add(value.getValue());
                }
                return row(values.toArray());
            });
        }
        addCollection(digest, "media", model.media().values().stream().toList(), value -> row(
                value.packagePath(), value.contentSha256(), value.contentSize()
        ));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static <T> void addCollection(
            MessageDigest digest,
            String name,
            List<T> values,
            Function<T, byte[]> encoder
    ) {
        updateField(digest, name);
        updateInt(digest, values.size());
        List<byte[]> rows = new ArrayList<>(values.size());
        for (T value : values) {
            rows.add(encoder.apply(value));
        }
        rows.sort(Arrays::compareUnsigned);
        for (byte[] bytes : rows) {
            updateInt(digest, bytes.length);
            digest.update(bytes);
        }
    }

    private static byte[] row(Object... values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(values.length);
                for (Object value : values) {
                    if (value == null) {
                        output.writeInt(-1);
                        continue;
                    }
                    byte[] encoded = canonical(value).getBytes(StandardCharsets.UTF_8);
                    output.writeInt(encoded.length);
                    output.write(encoded);
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("No se pudo construir la huella canónica.", impossible);
        }
    }

    private static String canonical(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Boolean bool) {
            return bool ? "1" : "0";
        }
        if (value instanceof TemporalAccessor temporal) {
            return temporal.toString();
        }
        return value.toString();
    }

    private static void updateField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update(new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        });
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 no está disponible.", impossible);
        }
    }
}
