package com.adelylria.ringlog.importexport.legacy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import com.adelylria.ringlog.importexport.ImportFormat;
import com.adelylria.ringlog.importexport.ImportSource;
import com.adelylria.ringlog.importexport.ImportValidationException;
import com.adelylria.ringlog.importexport.WorkbookFormatDetector;
import com.adelylria.ringlog.importexport.WorkbookProfile;
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

/** Strict reader for the already normalized and reconciled migrator v5.2 output. */
public final class LegacyV5WorkbookReader {

    private static final int MAX_ROWS_PER_SHEET = 250_000;
    private static final int MAX_PHOTOS = 5_000;
    private static final long MAX_MEDIA_BYTES = 512_000_000L;
    private static final long MAX_SINGLE_MEDIA_BYTES = 128_000_000L;
    private static final int MAX_CELL_CHARACTERS = 5_000_000;
    private static final long MAX_TOTAL_TEXT_CHARACTERS = 60_000_000L;

    private static final Set<String> EVENT_TYPES = Set.of("RINGING", "CONTROL", "RECOVERY");
    private static final Set<String> REVIEW_STATES = Set.of("OK", "REVIEW");
    private static final Set<String> CONFLICT_STATES = Set.of(
            "PENDING_REVIEW", "RESOLVED", "ACCEPTED_CANONICAL", "ACCEPTED_ALTERNATIVE"
    );

    private static final String[] SPECIES = {
            "species_key", "code", "scientific_name", "common_name", "active"
    };
    private static final String[] BIRDS = {"ring_number", "species_key"};
    private static final String[] PLACES = {
            "place_key", "name", "locality", "latitude", "longitude", "notes",
            "is_favorite", "is_default", "active"
    };
    private static final String[] EVENTS = {
            "event_key", "ring_number", "event_type", "event_date", "event_time",
            "place_key", "location_text", "sex_code", "age_euring_code", "fat_score",
            "muscle_score", "ringer_initials", "status", "reproductive_status",
            "moult_intensity", "moult_extension", "bird_condition", "return_status",
            "wing", "p3", "torso", "weight", "observations", "clouds", "rain",
            "thermal_sensation", "wind", "capture_type", "is_dead", "review_status",
            "review_note", "source_name", "source_reference", "source_aliases"
    };
    private static final String[] PHOTOS = {
            "event_key", "file_name", "file_path", "mime_type", "source_reference"
    };
    private static final String[] UNASSIGNED_PHOTOS = {
            "file_name", "file_path", "mime_type", "sha256", "width", "height",
            "source_reference"
    };
    private static final String[] SOURCE_RECORDS = {
            "source_name", "source_section", "source_reference", "ring_number", "raw_payload"
    };
    private static final String[] CONFLICTS = {
            "conflict_key", "ring_number", "conflict_type", "event_type", "field_name",
            "canonical_event_key", "canonical_source_reference", "canonical_value",
            "alternative_source_reference", "alternative_value", "canonical_event_snapshot",
            "alternative_event_snapshot", "status", "resolution_value", "note"
    };
    private static final String[] AUDIT = {
            "source_name", "source_section", "source_reference", "source_field",
            "original_value", "original_display", "destination", "normalized_value",
            "status", "note"
    };
    private static final String[] WARNINGS = {"severity", "source", "reference", "message"};

    public LegacyImportModel read(ImportSource source) throws ImportValidationException {
        WorkbookProfile profile = new WorkbookFormatDetector().detect(source);
        if (profile.format() != ImportFormat.LEGACY_V5) {
            throw new ImportValidationException(
                    "El lector legacy v5.2 recibió un formato diferente."
            );
        }
        CellValues values = new CellValues();
        MediaBudget mediaBudget = new MediaBudget();
        try (InputStream input = Files.newInputStream(source.workbookPath());
             Workbook workbook = WorkbookFactory.create(input)) {
            rejectFormulas(workbook);
            List<SpeciesRow> species = readSpecies(workbook, values);
            List<BirdRow> birds = readBirds(workbook, values);
            List<PlaceRow> places = readPlaces(workbook, values);
            List<EventRow> events = readEvents(workbook, values);
            List<PhotoRow> photos = readPhotos(workbook, values, source, mediaBudget);
            List<UnassignedPhotoRow> unassigned = readUnassignedPhotos(
                    workbook, values, source, mediaBudget
            );
            List<SourceRecordRow> sourceRecords = readSourceRecords(workbook, values);
            List<ConflictRow> conflicts = readConflicts(workbook, values);
            List<AuditRow> audit = readAudit(workbook, values);
            List<WarningRow> warnings = readWarnings(workbook, values);

            LegacyImportModel model = new LegacyImportModel(
                    profile.metadata(), species, birds, places, events, photos, unassigned,
                    sourceRecords, conflicts, audit, warnings
            );
            validateRelations(model);
            validateMetadataCounts(model);
            return model;
        } catch (ImportValidationException exception) {
            throw exception;
        } catch (EncryptedDocumentException exception) {
            throw new ImportValidationException("El legacy v5.2 está cifrado.", exception);
        } catch (IOException | RuntimeException exception) {
            throw new ImportValidationException(
                    "No se pudo leer el contenido legacy v5.2.", exception
            );
        }
    }

    private static List<SpeciesRow> readSpecies(Workbook workbook, CellValues values)
            throws ImportValidationException {
        List<SpeciesRow> result = new ArrayList<>();
        for (Row row : rows(workbook, "species", SPECIES)) {
            result.add(new SpeciesRow(
                    values.key(row, 0, "species_key"),
                    values.text(row, 1),
                    values.required(row, 2, "scientific_name"),
                    values.text(row, 3),
                    values.bool(row, 4, "active", true)
            ));
        }
        return List.copyOf(result);
    }

    private static List<BirdRow> readBirds(Workbook workbook, CellValues values)
            throws ImportValidationException {
        List<BirdRow> result = new ArrayList<>();
        for (Row row : rows(workbook, "birds", BIRDS)) {
            result.add(new BirdRow(
                    values.key(row, 0, "ring_number"),
                    values.key(row, 1, "species_key")
            ));
        }
        return List.copyOf(result);
    }

    private static List<PlaceRow> readPlaces(Workbook workbook, CellValues values)
            throws ImportValidationException {
        List<PlaceRow> result = new ArrayList<>();
        for (Row row : rows(workbook, "places", PLACES)) {
            BigDecimal latitude = values.decimal(row, 3, "latitude");
            BigDecimal longitude = values.decimal(row, 4, "longitude");
            range(latitude, new BigDecimal("-90"), new BigDecimal("90"), "latitude");
            range(longitude, new BigDecimal("-180"), new BigDecimal("180"), "longitude");
            result.add(new PlaceRow(
                    values.key(row, 0, "place_key"),
                    values.required(row, 1, "place.name"),
                    values.text(row, 2), latitude, longitude, values.text(row, 5),
                    values.bool(row, 6, "is_favorite", true),
                    values.bool(row, 7, "is_default", true),
                    values.bool(row, 8, "active", true)
            ));
        }
        return List.copyOf(result);
    }

    private static List<EventRow> readEvents(Workbook workbook, CellValues values)
            throws ImportValidationException {
        List<EventRow> result = new ArrayList<>();
        for (Row row : rows(workbook, "events", EVENTS)) {
            String eventType = values.required(row, 2, "event_type");
            if (!EVENT_TYPES.contains(eventType)) {
                throw new ImportValidationException("event_type no válido: " + eventType);
            }
            String review = values.required(row, 29, "review_status");
            if (!REVIEW_STATES.contains(review)) {
                throw new ImportValidationException("review_status no válido: " + review);
            }
            String aliasesText = values.text(row, 33);
            result.add(new EventRow(
                    values.key(row, 0, "event_key"),
                    values.key(row, 1, "ring_number"),
                    eventType,
                    values.date(row, 3, "event_date"),
                    values.time(row, 4, "event_time"),
                    values.optionalKey(row, 5, "place_key"),
                    values.text(row, 6), values.text(row, 7), values.text(row, 8),
                    values.integer(row, 9, "fat_score"),
                    values.integer(row, 10, "muscle_score"),
                    values.text(row, 11), values.text(row, 12), values.text(row, 13),
                    values.text(row, 14), values.text(row, 15), values.text(row, 16),
                    values.text(row, 17), nonNegative(values.decimal(row, 18, "wing"), "wing"),
                    nonNegative(values.decimal(row, 19, "p3"), "p3"),
                    nonNegative(values.decimal(row, 20, "torso"), "torso"),
                    nonNegative(values.decimal(row, 21, "weight"), "weight"),
                    values.text(row, 22), values.text(row, 23), values.text(row, 24),
                    values.decimal(row, 25, "thermal_sensation"), values.text(row, 26),
                    values.text(row, 27), values.bool(row, 28, "is_dead", true), review,
                    values.text(row, 30), values.text(row, 31), values.text(row, 32),
                    aliasesText, aliases(aliasesText)
            ));
        }
        return List.copyOf(result);
    }

    private static List<PhotoRow> readPhotos(
            Workbook workbook,
            CellValues values,
            ImportSource source,
            MediaBudget budget
    ) throws ImportValidationException, IOException {
        List<Row> rows = rows(workbook, "photos", PHOTOS);
        budget.addCount(rows.size());
        List<PhotoRow> result = new ArrayList<>();
        for (Row row : rows) {
            String filePath = values.required(row, 2, "photos.file_path");
            Path resolved = source.resolveMedia(filePath);
            FileIdentity identity = fileIdentity(resolved, budget);
            result.add(new PhotoRow(
                    values.key(row, 0, "photos.event_key"),
                    values.required(row, 1, "photos.file_name"), filePath,
                    values.text(row, 3), values.text(row, 4), resolved,
                    identity.sha256(), identity.size()
            ));
        }
        return List.copyOf(result);
    }

    private static List<UnassignedPhotoRow> readUnassignedPhotos(
            Workbook workbook,
            CellValues values,
            ImportSource source,
            MediaBudget budget
    ) throws ImportValidationException, IOException {
        List<Row> rows = rows(workbook, "unassigned_photos", UNASSIGNED_PHOTOS);
        budget.addCount(rows.size());
        List<UnassignedPhotoRow> result = new ArrayList<>();
        for (Row row : rows) {
            String filePath = values.required(row, 1, "unassigned_photos.file_path");
            String declared = values.required(row, 3, "unassigned_photos.sha256");
            if (!declared.matches("(?i)[0-9a-f]{64}")) {
                throw new ImportValidationException("SHA-256 declarado no válido: " + declared);
            }
            Path resolved = source.resolveMedia(filePath);
            FileIdentity identity = fileIdentity(resolved, budget);
            if (!declared.equalsIgnoreCase(identity.sha256())) {
                throw new ImportValidationException(
                        "La fotografía sin asignar no coincide con su SHA-256: " + filePath
                );
            }
            Integer width = values.integer(row, 4, "width");
            Integer height = values.integer(row, 5, "height");
            if ((width != null && width < 0) || (height != null && height < 0)) {
                throw new ImportValidationException("Las dimensiones de una fotografía son inválidas.");
            }
            result.add(new UnassignedPhotoRow(
                    values.required(row, 0, "unassigned_photos.file_name"), filePath,
                    values.text(row, 2), declared, width, height, values.text(row, 6),
                    resolved, identity.sha256(), identity.size()
            ));
        }
        return List.copyOf(result);
    }

    private static List<SourceRecordRow> readSourceRecords(Workbook workbook, CellValues values)
            throws ImportValidationException {
        List<SourceRecordRow> result = new ArrayList<>();
        for (Row row : rows(workbook, "source_records", SOURCE_RECORDS)) {
            result.add(new SourceRecordRow(
                    values.text(row, 0), values.text(row, 1),
                    values.required(row, 2, "source_records.source_reference"),
                    values.text(row, 3), values.requiredPreservingEmpty(
                            row, 4, "source_records.raw_payload"
                    )
            ));
        }
        return List.copyOf(result);
    }

    private static List<ConflictRow> readConflicts(Workbook workbook, CellValues values)
            throws ImportValidationException {
        List<ConflictRow> result = new ArrayList<>();
        for (Row row : rows(workbook, "migration_conflicts", CONFLICTS)) {
            String status = values.required(row, 12, "migration_conflicts.status");
            if (!CONFLICT_STATES.contains(status)) {
                throw new ImportValidationException("Estado de conflicto no válido: " + status);
            }
            result.add(new ConflictRow(
                    values.key(row, 0, "conflict_key"), values.text(row, 1),
                    values.required(row, 2, "conflict_type"), values.text(row, 3),
                    values.text(row, 4), values.key(row, 5, "canonical_event_key"),
                    values.text(row, 6), values.text(row, 7), values.text(row, 8),
                    values.text(row, 9), values.text(row, 10), values.text(row, 11),
                    status, values.text(row, 13), values.text(row, 14)
            ));
        }
        return List.copyOf(result);
    }

    private static List<AuditRow> readAudit(Workbook workbook, CellValues values)
            throws ImportValidationException {
        List<AuditRow> result = new ArrayList<>();
        for (Row row : rows(workbook, "migration_audit", AUDIT)) {
            result.add(new AuditRow(
                    values.text(row, 0), values.text(row, 1), values.text(row, 2),
                    values.text(row, 3), values.text(row, 4), values.text(row, 5),
                    values.text(row, 6), values.text(row, 7),
                    values.required(row, 8, "migration_audit.status"), values.text(row, 9)
            ));
        }
        return List.copyOf(result);
    }

    private static List<WarningRow> readWarnings(Workbook workbook, CellValues values)
            throws ImportValidationException {
        List<WarningRow> result = new ArrayList<>();
        for (Row row : rows(workbook, "migration_warnings", WARNINGS)) {
            result.add(new WarningRow(
                    values.text(row, 0), values.text(row, 1), values.text(row, 2),
                    values.requiredPreservingEmpty(row, 3, "migration_warnings.message")
            ));
        }
        return List.copyOf(result);
    }

    private static void validateRelations(LegacyImportModel model)
            throws ImportValidationException {
        Set<String> species = unique(model.species().stream().map(SpeciesRow::speciesKey).toList(),
                "species_key");
        Set<String> birds = unique(model.birds().stream().map(BirdRow::ringNumber).toList(),
                "ring_number");
        Set<String> places = unique(model.places().stream().map(PlaceRow::placeKey).toList(),
                "place_key");
        Set<String> events = unique(model.events().stream().map(EventRow::eventKey).toList(),
                "event_key");
        unique(model.conflicts().stream().map(ConflictRow::conflictKey).toList(), "conflict_key");

        for (BirdRow bird : model.birds()) {
            if (!species.contains(bird.speciesKey())) {
                throw new ImportValidationException(
                        "La anilla " + bird.ringNumber() + " apunta a una species_key inexistente."
                );
            }
        }
        long defaults = model.places().stream().filter(PlaceRow::defaultPlace).count();
        if (defaults > 1) {
            throw new ImportValidationException("Hay más de un lugar marcado como predeterminado.");
        }
        for (EventRow event : model.events()) {
            if (!birds.contains(event.ringNumber())) {
                throw new ImportValidationException(
                        "El evento " + event.eventKey() + " apunta a una anilla inexistente."
                );
            }
            if (event.placeKey() != null && !places.contains(event.placeKey())) {
                throw new ImportValidationException(
                        "El evento " + event.eventKey() + " apunta a un place_key inexistente."
                );
            }
        }
        for (PhotoRow photo : model.photos()) {
            if (!events.contains(photo.eventKey())) {
                throw new ImportValidationException(
                        "Una fotografía apunta a un event_key inexistente: " + photo.eventKey()
                );
            }
        }
        for (ConflictRow conflict : model.conflicts()) {
            if (!events.contains(conflict.canonicalEventKey())) {
                throw new ImportValidationException(
                        "El conflicto " + conflict.conflictKey()
                                + " apunta a un canonical_event_key inexistente."
                );
            }
        }
        boolean unsafeAudit = model.audit().stream().anyMatch(row ->
                "LOST".equals(row.status()) || "UNMAPPED".equals(row.status())
        );
        if (unsafeAudit) {
            throw new ImportValidationException(
                    "La auditoría contiene valores LOST o UNMAPPED; no se puede importar."
            );
        }
    }

    private static void validateMetadataCounts(LegacyImportModel model)
            throws ImportValidationException {
        Map<String, String> metadata = model.metadata();
        count(metadata, "species_count", model.species().size());
        count(metadata, "birds_count", model.birds().size());
        count(metadata, "places_count", model.places().size());
        count(metadata, "events_count", model.events().size());
        count(metadata, "photos_count", model.photos().size());
        count(metadata, "unassigned_photos_count", model.unassignedPhotos().size());
        count(metadata, "source_records_count", model.sourceRecords().size());
        count(metadata, "audit_records_count", model.audit().size());
        count(metadata, "migration_conflicts_count", model.conflicts().size());
        count(metadata, "warnings_count", model.warnings().size());
        count(metadata, "events_review_count",
                model.events().stream().filter(row -> "REVIEW".equals(row.reviewStatus())).count());
        count(metadata, "audit_lost_count",
                model.audit().stream().filter(row -> "LOST".equals(row.status())).count());
        count(metadata, "audit_unmapped_count",
                model.audit().stream().filter(row -> "UNMAPPED".equals(row.status())).count());
        count(metadata, "audit_conflict_count",
                model.audit().stream().filter(row -> "CONFLICT".equals(row.status())).count());
        count(metadata, "conflicts_pending_review_count",
                model.conflicts().stream().filter(row ->
                        "PENDING_REVIEW".equals(row.status())).count());
        count(metadata, "conflicts_resolved_count",
                model.conflicts().stream().filter(row -> "RESOLVED".equals(row.status())).count());
    }

    private static void count(Map<String, String> metadata, String key, long actual)
            throws ImportValidationException {
        String declared = metadata.get(key);
        if (declared == null) {
            throw new ImportValidationException("Falta el contador metadata " + key + ".");
        }
        try {
            if (Long.parseLong(declared) != actual) {
                throw new ImportValidationException(
                        "El contador metadata " + key + " no coincide con las filas reales."
                );
            }
        } catch (NumberFormatException exception) {
            throw new ImportValidationException("El contador metadata " + key + " no es válido.");
        }
    }

    private static List<Row> rows(Workbook workbook, String sheetName, String[] headers)
            throws ImportValidationException {
        Sheet sheet = exactSheet(workbook, sheetName);
        if (sheet == null) {
            throw new ImportValidationException("Falta la hoja " + sheetName + ".");
        }
        Row header = sheet.getRow(0);
        if (header == null) {
            throw new ImportValidationException("La hoja " + sheetName + " no tiene cabecera.");
        }
        for (int column = 0; column < headers.length; column++) {
            Cell cell = header.getCell(column);
            if (cell == null || cell.getCellType() != CellType.STRING
                    || !headers[column].equals(cell.getStringCellValue())) {
                throw new ImportValidationException(
                        "Cabecera no válida en " + sheetName + ": se esperaba " + headers[column]
                );
            }
        }
        rejectExtraCells(header, headers.length, sheetName);
        if (sheet.getLastRowNum() > MAX_ROWS_PER_SHEET) {
            throw new ImportValidationException("La hoja " + sheetName + " contiene demasiadas filas.");
        }
        List<Row> result = new ArrayList<>();
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row == null || empty(row)) {
                continue;
            }
            rejectExtraCells(row, headers.length, sheetName);
            result.add(row);
        }
        return List.copyOf(result);
    }

    private static void rejectFormulas(Workbook workbook) throws ImportValidationException {
        for (String name : ImportFormat.LEGACY_V5.requiredSheets()) {
            Sheet sheet = exactSheet(workbook, name);
            if (sheet == null) {
                continue;
            }
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell.getCellType() == CellType.FORMULA) {
                        throw new ImportValidationException(
                                "No se permiten fórmulas en el legacy v5.2 (" + name + ")."
                        );
                    }
                }
            }
        }
    }

    private static Sheet exactSheet(Workbook workbook, String name) {
        for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
            if (name.equals(workbook.getSheetName(index))) {
                return workbook.getSheetAt(index);
            }
        }
        return null;
    }

    private static void rejectExtraCells(Row row, int expected, String sheet)
            throws ImportValidationException {
        for (int column = expected; column < row.getLastCellNum(); column++) {
            Cell cell = row.getCell(column);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                throw new ImportValidationException(
                        "La hoja " + sheet + " contiene columnas inesperadas."
                );
            }
        }
    }

    private static boolean empty(Row row) {
        for (Cell cell : row) {
            if (cell.getCellType() != CellType.BLANK) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> unique(List<String> values, String field)
            throws ImportValidationException {
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            if (!unique.add(value)) {
                throw new ImportValidationException("Valor duplicado en " + field + ": " + value);
            }
        }
        return Set.copyOf(unique);
    }

    private static List<String> aliases(String raw) throws ImportValidationException {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        String[] split = raw.split(Pattern.quote(" | "), -1);
        List<String> result = new ArrayList<>(split.length);
        Set<String> unique = new HashSet<>();
        for (String alias : split) {
            if (alias.isBlank() || !alias.equals(alias.trim()) || !unique.add(alias)) {
                throw new ImportValidationException("source_aliases contiene una referencia inválida.");
            }
            result.add(alias);
        }
        return List.copyOf(result);
    }

    private static BigDecimal nonNegative(BigDecimal value, String field)
            throws ImportValidationException {
        if (value != null && value.signum() < 0) {
            throw new ImportValidationException(field + " no puede ser negativo.");
        }
        return value;
    }

    private static void range(BigDecimal value, BigDecimal minimum, BigDecimal maximum, String field)
            throws ImportValidationException {
        if (value != null && (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0)) {
            throw new ImportValidationException(field + " está fuera de rango.");
        }
    }

    private static FileIdentity fileIdentity(Path path, MediaBudget budget)
            throws IOException, ImportValidationException {
        long expectedSize = Files.size(path);
        if (expectedSize < 0 || expectedSize > MAX_SINGLE_MEDIA_BYTES) {
            throw new ImportValidationException("Una fotografía supera el límite seguro.");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 no está disponible.", impossible);
        }
        long actualSize = 0;
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) != -1) {
                actualSize += read;
                if (actualSize > MAX_SINGLE_MEDIA_BYTES) {
                    throw new ImportValidationException("Una fotografía supera el límite seguro.");
                }
                digest.update(buffer, 0, read);
            }
        }
        if (actualSize != expectedSize || Files.size(path) != expectedSize) {
            throw new ImportValidationException(
                    "Una fotografía cambió mientras se analizaba; vuelve a intentar la importación."
            );
        }
        budget.addBytes(actualSize);
        return new FileIdentity(HexFormat.of().formatHex(digest.digest()), actualSize);
    }

    private record FileIdentity(String sha256, long size) {
    }

    private static final class MediaBudget {
        private int count;
        private long bytes;

        private void addCount(int amount) throws ImportValidationException {
            count += amount;
            if (count > MAX_PHOTOS) {
                throw new ImportValidationException("El legacy contiene demasiadas fotografías.");
            }
        }

        private void addBytes(long amount) throws ImportValidationException {
            if (amount < 0 || amount > MAX_SINGLE_MEDIA_BYTES) {
                throw new ImportValidationException("Una fotografía supera el límite seguro.");
            }
            bytes += amount;
            if (bytes > MAX_MEDIA_BYTES) {
                throw new ImportValidationException("Las fotografías superan el límite total seguro.");
            }
        }
    }

    private static final class CellValues {
        private long totalCharacters;

        private String key(Row row, int column, String field) throws ImportValidationException {
            String value = required(row, column, field);
            if (!value.equals(value.trim())) {
                throw new ImportValidationException(field + " contiene espacios exteriores.");
            }
            return value;
        }

        private String optionalKey(Row row, int column, String field)
                throws ImportValidationException {
            String value = text(row, column);
            if (value != null && (value.isBlank() || !value.equals(value.trim()))) {
                throw new ImportValidationException(field + " no es una clave válida.");
            }
            return value;
        }

        private String required(Row row, int column, String field)
                throws ImportValidationException {
            String value = text(row, column);
            if (value == null || value.isBlank()) {
                throw new ImportValidationException("Falta el valor obligatorio " + field + ".");
            }
            return value;
        }

        private String requiredPreservingEmpty(Row row, int column, String field)
                throws ImportValidationException {
            String value = text(row, column);
            if (value == null) {
                throw new ImportValidationException("Falta el valor obligatorio " + field + ".");
            }
            return value;
        }

        private String text(Row row, int column) throws ImportValidationException {
            Cell cell = row.getCell(column);
            if (cell == null || cell.getCellType() == CellType.BLANK) {
                return null;
            }
            if (cell.getCellType() != CellType.STRING) {
                throw new ImportValidationException(
                        "Se esperaba texto en la fila " + (row.getRowNum() + 1)
                                + ", columna " + (column + 1) + "."
                );
            }
            String value = cell.getStringCellValue();
            if (value.length() > MAX_CELL_CHARACTERS) {
                throw new ImportValidationException("Una celda de texto supera el límite seguro.");
            }
            totalCharacters += value.length();
            if (totalCharacters > MAX_TOTAL_TEXT_CHARACTERS) {
                throw new ImportValidationException("El texto del legacy supera el límite seguro.");
            }
            return value;
        }

        private boolean bool(Row row, int column, String field, boolean required)
                throws ImportValidationException {
            Cell cell = row.getCell(column);
            if (cell == null || cell.getCellType() == CellType.BLANK) {
                if (required) {
                    throw new ImportValidationException("Falta el booleano " + field + ".");
                }
                return false;
            }
            if (cell.getCellType() == CellType.BOOLEAN) {
                return cell.getBooleanCellValue();
            }
            if (cell.getCellType() == CellType.NUMERIC) {
                double number = cell.getNumericCellValue();
                if (number == 0d || number == 1d) {
                    return number == 1d;
                }
            }
            throw new ImportValidationException(field + " debe ser 0 o 1.");
        }

        private Integer integer(Row row, int column, String field)
                throws ImportValidationException {
            Cell cell = row.getCell(column);
            if (cell == null || cell.getCellType() == CellType.BLANK) {
                return null;
            }
            if (cell.getCellType() != CellType.NUMERIC) {
                throw new ImportValidationException(field + " debe ser un número entero.");
            }
            double number = cell.getNumericCellValue();
            if (!Double.isFinite(number) || number != Math.rint(number)
                    || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                throw new ImportValidationException(field + " no es un entero válido.");
            }
            return (int) number;
        }

        private BigDecimal decimal(Row row, int column, String field)
                throws ImportValidationException {
            Cell cell = row.getCell(column);
            if (cell == null || cell.getCellType() == CellType.BLANK) {
                return null;
            }
            if (cell.getCellType() != CellType.NUMERIC
                    || !Double.isFinite(cell.getNumericCellValue())) {
                throw new ImportValidationException(field + " debe ser un número válido.");
            }
            return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros();
        }

        private LocalDate date(Row row, int column, String field)
                throws ImportValidationException {
            Cell cell = row.getCell(column);
            if (cell == null || cell.getCellType() == CellType.BLANK) {
                throw new ImportValidationException("Falta la fecha " + field + ".");
            }
            try {
                if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toLocalDate();
                }
                if (cell.getCellType() == CellType.STRING) {
                    return LocalDate.parse(cell.getStringCellValue());
                }
            } catch (DateTimeException | IllegalStateException exception) {
                throw new ImportValidationException(field + " no es una fecha válida.");
            }
            throw new ImportValidationException(field + " no es una fecha válida.");
        }

        private LocalTime time(Row row, int column, String field)
                throws ImportValidationException {
            Cell cell = row.getCell(column);
            if (cell == null || cell.getCellType() == CellType.BLANK) {
                return null;
            }
            try {
                if (cell.getCellType() == CellType.STRING) {
                    return LocalTime.parse(cell.getStringCellValue());
                }
                if (cell.getCellType() == CellType.NUMERIC) {
                    return DateUtil.getLocalDateTime(cell.getNumericCellValue()).toLocalTime();
                }
            } catch (DateTimeException | IllegalStateException exception) {
                throw new ImportValidationException(field + " no es una hora válida.");
            }
            throw new ImportValidationException(field + " no es una hora válida.");
        }
    }
}
