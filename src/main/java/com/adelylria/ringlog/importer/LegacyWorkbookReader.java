package com.adelylria.ringlog.importer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

public final class LegacyWorkbookReader {

    private static final String FORMAT = "RingLog Import";
    private static final int BACKUP_FORMAT_VERSION = 3;
    private static final int MIGRATOR_FORMAT_VERSION = 4;
    private static final Set<Integer> FORMAT_VERSIONS = Set.of(2, 3, 4);
    private static final long MAX_WORKBOOK_BYTES = 128_000_000L;
    private static final int MAX_PHOTOS = 5_000;
    private static final int MAX_INTERNAL_ROWS = 100_000;
    private static final int MAX_TEXT_KEYS = 50_000;
    private static final int MAX_CHUNKS_PER_PHOTO = 1_000;
    private static final long MAX_PHOTO_BASE64_CHARACTERS = 30_000_000L;
    private static final long MAX_TOTAL_BASE64_CHARACTERS = 80_000_000L;
    private static final int MAX_TEXT_CHUNKS = 200;
    private static final long MAX_TEXT_CHARACTERS = 5_000_000L;
    private static final long MAX_TOTAL_TEXT_CHARACTERS = 20_000_000L;
    private static final String TEXT_REFERENCE_PREFIX = "@@RINGLOG_TEXT:";
    private static final Set<String> EVENT_TYPES = Set.of(
            "RINGING",
            "CONTROL",
            "RECOVERY"
    );
    private static final DateTimeFormatter FLEXIBLE_TIME = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.HOUR_OF_DAY)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .optionalStart()
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .optionalEnd()
            .toFormatter(Locale.ROOT);

    private DataFormatter formatter;
    private Map<String, String> overflowTexts = Map.of();
    private boolean resolveOverflowReferences;
    private boolean preserveTextWhitespace;

    LegacyImportData read(Path workbookPath) throws LegacyImportException {
        if (workbookPath == null) {
            throw new LegacyImportException("Selecciona el archivo ringlog-import.xlsx.");
        }
        Path workbookFile = workbookPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(workbookFile)) {
            throw new LegacyImportException("No se encuentra el archivo de importación.");
        }
        try {
            if (Files.size(workbookFile) > MAX_WORKBOOK_BYTES) {
                throw new LegacyImportException(
                        "El archivo supera el tamaño máximo admitido para una importación."
                );
            }
        } catch (IOException exception) {
            throw new LegacyImportException(
                    "No se pudo comprobar el tamaño del archivo de importación.",
                    exception
            );
        }

        try (InputStream input = Files.newInputStream(workbookFile);
             Workbook workbook = WorkbookFactory.create(input)) {
            formatter = new DataFormatter(Locale.ROOT, true);
            preserveTextWhitespace = false;
            rejectFormulas(workbook);
            boolean hasTextContent = workbook.getSheet("text_content") != null;
            resolveOverflowReferences = hasTextContent;
            overflowTexts = readTextContent(workbook);

            Map<String, String> metadata = readMetadata(workbook);
            int formatVersion = validateFormat(metadata);
            rejectMisdeclaredFormat(workbook, metadata, formatVersion);
            if (formatVersion == MIGRATOR_FORMAT_VERSION && hasTextContent) {
                throw new LegacyImportException(
                        "El formato 4 no admite la hoja interna text_content de las copias."
                );
            }
            preserveTextWhitespace = formatVersion >= 3;
            if (isBackupFormat(formatVersion) && !hasTextContent) {
                throw new LegacyImportException(
                        "Falta la hoja interna de textos de la copia de seguridad."
                );
            }

            List<SpeciesRow> species = readSpecies(workbook, formatVersion);
            List<BirdRow> birds = readBirds(workbook, formatVersion);
            List<PlaceRow> places = readPlaces(workbook, formatVersion);
            List<EventRow> events = readEvents(workbook, formatVersion);
            List<PhotoRow> photos = readPhotos(workbook, formatVersion);
            if (formatVersion == MIGRATOR_FORMAT_VERSION) {
                validateMigratorAudit(workbook, metadata);
            }
            List<ImportWarning> warnings = readWarnings(workbook);

            validateRelationships(
                    workbookFile,
                    formatVersion,
                    species,
                    birds,
                    places,
                    events,
                    photos
            );
            validateCount(metadata, "species_count", species.size());
            validateCount(metadata, "birds_count", birds.size());
            validateCount(metadata, "places_count", places.size());
            validateCount(metadata, "events_count", events.size());
            validateCount(metadata, "photos_count", photos.size());
            validateCount(metadata, "warnings_count", warnings.size());

            return new LegacyImportData(
                    workbookFile,
                    formatVersion,
                    species,
                    birds,
                    places,
                    events,
                    photos,
                    warnings
            );
        } catch (LegacyImportException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new LegacyImportException(
                    "No se pudo leer el archivo de importación. Comprueba que sea un XLSX válido.",
                    exception
            );
        }
    }

    /**
     * Compatibility-only bridge for the public v3 staging pipeline. This exposes parsed rows,
     * never the old importer's inference or merge behavior.
     */
    public com.adelylria.ringlog.importexport.compatibility.RingLogV3BackupModel readBackupV3(
            Path workbookPath,
            Map<String, String> metadata
    ) throws LegacyImportException {
        LegacyImportData data = read(workbookPath);
        if (data.formatVersion() != 3) {
            throw new LegacyImportException("La copia no utiliza el formato RingLog v3.");
        }
        return new com.adelylria.ringlog.importexport.compatibility.RingLogV3BackupModel(
                metadata,
                data.species().stream().map(row ->
                        new com.adelylria.ringlog.importexport.compatibility
                                .RingLogV3BackupModel.SpeciesRow(
                                row.key(), row.code(), row.scientificName(), row.commonName(),
                                row.active(), row.createdAt(), row.updatedAt()
                        )).toList(),
                data.birds().stream().map(row ->
                        new com.adelylria.ringlog.importexport.compatibility
                                .RingLogV3BackupModel.BirdRow(
                                row.ringNumber(), row.speciesKey(), row.createdAt(), row.updatedAt()
                        )).toList(),
                data.places().stream().map(row ->
                        new com.adelylria.ringlog.importexport.compatibility
                                .RingLogV3BackupModel.PlaceRow(
                                row.key(), row.name(), row.locality(), row.latitude(),
                                row.longitude(), row.notes(), row.favorite(), row.defaultPlace(),
                                row.active(), row.createdAt(), row.updatedAt()
                        )).toList(),
                data.events().stream().map(row ->
                        new com.adelylria.ringlog.importexport.compatibility
                                .RingLogV3BackupModel.EventRow(
                                row.key(), row.ringNumber(), row.eventType(), row.eventDate(),
                                row.eventTime(), row.placeKey(), row.locationText(), row.sexCode(),
                                row.ageEuringCode(), row.fatScore(), row.muscleScore(),
                                row.ringerInitials(), row.status(), row.reproductiveStatus(),
                                row.moultIntensity(), row.moultExtension(), row.birdCondition(),
                                row.returnStatus(), row.wing(), row.p3(), row.torso(), row.weight(),
                                row.observations(), row.clouds(), row.rain(), row.thermalSensation(),
                                row.wind(), row.captureType(), row.dead(), row.sourceName(),
                                row.sourceReference(), row.sourceAliases(), row.createdAt(),
                                row.updatedAt()
                        )).toList(),
                data.photos().stream().map(row ->
                        new com.adelylria.ringlog.importexport.compatibility
                                .RingLogV3BackupModel.PhotoRow(
                                row.eventKey(), row.fileName(), row.relativePath(), row.mimeType(),
                                row.sourceReference(), row.photoKey(), row.contentHash(),
                                row.embeddedContent(), row.createdAt()
                        )).toList(),
                data.warnings().stream().map(row ->
                        new com.adelylria.ringlog.importexport.compatibility
                                .RingLogV3BackupModel.WarningRow(
                                row.severity(), row.source(), row.reference(), row.message()
                        )).toList()
        );
    }

    private Map<String, String> readMetadata(Workbook workbook)
            throws LegacyImportException {
        Sheet sheet = requiredSheet(workbook, "metadata");
        Map<String, Integer> columns = columns(sheet, "key", "value");
        Map<String, String> metadata = new LinkedHashMap<>();
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (isEmpty(row)) {
                continue;
            }
            String key = requiredText(row, columns, "key", sheet, rowIndex);
            String value = requiredText(row, columns, "value", sheet, rowIndex);
            if (metadata.putIfAbsent(key, value) != null) {
                throw invalid(sheet, rowIndex, "La clave de metadata está repetida: " + key);
            }
        }
        return metadata;
    }

    private int validateFormat(Map<String, String> metadata)
            throws LegacyImportException {
        if (!FORMAT.equals(metadata.get("format"))) {
            throw new LegacyImportException(
                    "El archivo no es una exportación compatible de RingLog."
            );
        }
        String value = metadata.get("format_version");
        int version;
        try {
            version = Integer.parseInt(value);
        } catch (NumberFormatException | NullPointerException exception) {
            throw new LegacyImportException(
                    "La versión del archivo no es válida.",
                    exception
            );
        }
        if (!FORMAT_VERSIONS.contains(version)) {
            throw new LegacyImportException(
                    "La versión del archivo no es compatible. Se admiten las versiones 2, 3 y 4."
            );
        }
        return version;
    }

    private void rejectMisdeclaredFormat(
            Workbook workbook,
            Map<String, String> metadata,
            int formatVersion
    ) throws LegacyImportException {
        if (formatVersion != 2) {
            return;
        }
        boolean hasVersion4Sheet = workbook.getSheet("source_records") != null
                || workbook.getSheet("migration_audit") != null;
        boolean hasVersion4Metadata = metadata.keySet().stream().anyMatch(key ->
                key.startsWith("audit_") || "source_records_count".equals(key)
        );
        if (hasVersion4Sheet || hasVersion4Metadata) {
            throw new LegacyImportException(
                    "El archivo declara la versión 2, pero contiene la auditoría de la versión 4. "
                            + "Vuelve a generarlo sin modificar su metadata."
            );
        }
    }

    private List<SpeciesRow> readSpecies(Workbook workbook, int formatVersion)
            throws LegacyImportException {
        Sheet sheet = requiredSheet(workbook, "species");
        Map<String, Integer> columns = isBackupFormat(formatVersion)
                ? columns(
                        sheet,
                        "species_key",
                        "code",
                        "scientific_name",
                        "common_name",
                        "active",
                        "created_at",
                        "updated_at"
                )
                : columns(
                        sheet,
                        "species_key",
                        "code",
                        "scientific_name",
                        "common_name",
                        "active"
                );
        List<SpeciesRow> rows = new ArrayList<>();
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (isEmpty(row)) {
                continue;
            }
            rows.add(new SpeciesRow(
                    requiredText(row, columns, "species_key", sheet, rowIndex),
                    optionalText(row, columns, "code"),
                    requiredText(row, columns, "scientific_name", sheet, rowIndex),
                    optionalText(row, columns, "common_name"),
                    requiredBoolean(row, columns, "active", sheet, rowIndex),
                    isBackupFormat(formatVersion)
                            ? requiredText(row, columns, "created_at", sheet, rowIndex)
                            : null,
                    isBackupFormat(formatVersion)
                            ? optionalText(row, columns, "updated_at")
                            : null
            ));
        }
        return rows;
    }

    private List<BirdRow> readBirds(Workbook workbook, int formatVersion)
            throws LegacyImportException {
        Sheet sheet = requiredSheet(workbook, "birds");
        Map<String, Integer> columns = isBackupFormat(formatVersion)
                ? columns(
                        sheet,
                        "ring_number",
                        "species_key",
                        "created_at",
                        "updated_at"
                )
                : columns(sheet, "ring_number", "species_key");
        List<BirdRow> rows = new ArrayList<>();
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (isEmpty(row)) {
                continue;
            }
            rows.add(new BirdRow(
                    requiredText(row, columns, "ring_number", sheet, rowIndex),
                    requiredText(row, columns, "species_key", sheet, rowIndex),
                    isBackupFormat(formatVersion)
                            ? requiredText(row, columns, "created_at", sheet, rowIndex)
                            : null,
                    isBackupFormat(formatVersion)
                            ? optionalText(row, columns, "updated_at")
                            : null
            ));
        }
        return rows;
    }

    private List<PlaceRow> readPlaces(Workbook workbook, int formatVersion)
            throws LegacyImportException {
        Sheet sheet = requiredSheet(workbook, "places");
        Map<String, Integer> columns = isBackupFormat(formatVersion)
                ? columns(
                        sheet,
                        "place_key",
                        "name",
                        "locality",
                        "latitude",
                        "longitude",
                        "notes",
                        "is_favorite",
                        "is_default",
                        "active",
                        "created_at",
                        "updated_at"
                )
                : columns(
                        sheet,
                        "place_key",
                        "name",
                        "locality",
                        "latitude",
                        "longitude",
                        "notes",
                        "is_favorite",
                        "is_default",
                        "active"
                );
        List<PlaceRow> rows = new ArrayList<>();
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (isEmpty(row)) {
                continue;
            }
            Double latitude = optionalDouble(row, columns, "latitude", sheet, rowIndex);
            Double longitude = optionalDouble(row, columns, "longitude", sheet, rowIndex);
            if (latitude != null && (latitude < -90 || latitude > 90)) {
                throw invalid(sheet, rowIndex, "La latitud debe estar entre -90 y 90.");
            }
            if (longitude != null && (longitude < -180 || longitude > 180)) {
                throw invalid(sheet, rowIndex, "La longitud debe estar entre -180 y 180.");
            }
            rows.add(new PlaceRow(
                    requiredText(row, columns, "place_key", sheet, rowIndex),
                    requiredText(row, columns, "name", sheet, rowIndex),
                    optionalText(row, columns, "locality"),
                    latitude,
                    longitude,
                    optionalText(row, columns, "notes"),
                    requiredBoolean(row, columns, "is_favorite", sheet, rowIndex),
                    requiredBoolean(row, columns, "is_default", sheet, rowIndex),
                    requiredBoolean(row, columns, "active", sheet, rowIndex),
                    isBackupFormat(formatVersion)
                            ? requiredText(row, columns, "created_at", sheet, rowIndex)
                            : null,
                    isBackupFormat(formatVersion)
                            ? optionalText(row, columns, "updated_at")
                            : null
            ));
        }
        return rows;
    }

    private List<EventRow> readEvents(Workbook workbook, int formatVersion)
            throws LegacyImportException {
        Sheet sheet = requiredSheet(workbook, "events");
        String[] baseColumns = {
                "event_key", "ring_number", "event_type", "event_date", "event_time",
                "place_key", "location_text", "sex_code", "age_euring_code",
                "fat_score", "muscle_score", "ringer_initials", "status",
                "reproductive_status", "moult_intensity", "moult_extension",
                "bird_condition", "return_status", "wing", "p3", "torso", "weight",
                "observations", "clouds", "rain", "thermal_sensation", "wind",
                "capture_type", "is_dead", "source_name", "source_reference",
                "source_aliases"
        };
        String[] requiredColumns = isBackupFormat(formatVersion)
                ? java.util.stream.Stream.concat(
                        java.util.Arrays.stream(baseColumns),
                        java.util.stream.Stream.of("created_at", "updated_at")
                ).toArray(String[]::new)
                : baseColumns;
        Map<String, Integer> columns = columns(sheet, requiredColumns);
        List<EventRow> rows = new ArrayList<>();
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (isEmpty(row)) {
                continue;
            }
            String eventType = requiredText(
                    row,
                    columns,
                    "event_type",
                    sheet,
                    rowIndex
            ).toUpperCase(Locale.ROOT);
            if (!EVENT_TYPES.contains(eventType)) {
                throw invalid(sheet, rowIndex, "Tipo de evento no válido: " + eventType);
            }
            Double wing = nonNegative(row, columns, "wing", sheet, rowIndex);
            Double p3 = nonNegative(row, columns, "p3", sheet, rowIndex);
            Double torso = nonNegative(row, columns, "torso", sheet, rowIndex);
            Double weight = nonNegative(row, columns, "weight", sheet, rowIndex);
            rows.add(new EventRow(
                    requiredText(row, columns, "event_key", sheet, rowIndex),
                    requiredText(row, columns, "ring_number", sheet, rowIndex),
                    eventType,
                    requiredDate(row, columns, "event_date", sheet, rowIndex),
                    optionalTime(row, columns, "event_time", sheet, rowIndex),
                    optionalText(row, columns, "place_key"),
                    optionalText(row, columns, "location_text"),
                    optionalText(row, columns, "sex_code"),
                    optionalText(row, columns, "age_euring_code"),
                    optionalInteger(row, columns, "fat_score", sheet, rowIndex),
                    optionalInteger(row, columns, "muscle_score", sheet, rowIndex),
                    optionalText(row, columns, "ringer_initials"),
                    optionalText(row, columns, "status"),
                    optionalText(row, columns, "reproductive_status"),
                    optionalText(row, columns, "moult_intensity"),
                    optionalText(row, columns, "moult_extension"),
                    optionalText(row, columns, "bird_condition"),
                    optionalText(row, columns, "return_status"),
                    wing,
                    p3,
                    torso,
                    weight,
                    optionalText(row, columns, "observations"),
                    optionalText(row, columns, "clouds"),
                    optionalText(row, columns, "rain"),
                    optionalText(row, columns, "thermal_sensation"),
                    optionalText(row, columns, "wind"),
                    optionalText(row, columns, "capture_type"),
                    requiredBoolean(row, columns, "is_dead", sheet, rowIndex),
                    isBackupFormat(formatVersion)
                            ? optionalText(row, columns, "source_name")
                            : requiredText(row, columns, "source_name", sheet, rowIndex),
                    isBackupFormat(formatVersion)
                            ? optionalText(row, columns, "source_reference")
                            : requiredText(
                                    row,
                                    columns,
                                    "source_reference",
                                    sheet,
                                    rowIndex
                            ),
                    aliases(optionalText(row, columns, "source_aliases")),
                    isBackupFormat(formatVersion)
                            ? requiredText(row, columns, "created_at", sheet, rowIndex)
                            : null,
                    isBackupFormat(formatVersion)
                            ? optionalText(row, columns, "updated_at")
                            : null
            ));
        }
        return rows;
    }

    private List<PhotoRow> readPhotos(Workbook workbook, int formatVersion)
            throws LegacyImportException {
        Sheet sheet = requiredSheet(workbook, "photos");
        Map<String, Integer> columns = isBackupFormat(formatVersion)
                ? columns(
                        sheet,
                        "event_key",
                        "file_name",
                        "file_path",
                        "mime_type",
                        "source_reference",
                        "photo_key",
                        "sha256",
                        "created_at"
                )
                : columns(
                        sheet,
                        "event_key",
                        "file_name",
                        "file_path",
                        "mime_type",
                        "source_reference"
                );
        Map<String, byte[]> embedded = isBackupFormat(formatVersion)
                ? readPhotoContent(workbook)
                : Map.of();
        List<PhotoRow> rows = new ArrayList<>();
        Set<String> usedPhotoKeys = new HashSet<>();
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (isEmpty(row)) {
                continue;
            }
            String photoKey = isBackupFormat(formatVersion)
                    ? requiredText(row, columns, "photo_key", sheet, rowIndex)
                    : null;
            String contentHash = isBackupFormat(formatVersion)
                    ? requiredText(row, columns, "sha256", sheet, rowIndex)
                    : null;
            if (photoKey != null && !usedPhotoKeys.add(normalizeKey(photoKey))) {
                throw invalid(
                        sheet,
                        rowIndex,
                        "La clave interna de foto está repetida: " + photoKey + "."
                );
            }
            byte[] content = photoKey == null
                    ? null
                    : embedded.get(normalizeKey(photoKey));
            if (isBackupFormat(formatVersion) && content == null) {
                throw invalid(
                        sheet,
                        rowIndex,
                        "No se encuentran los datos internos de la foto " + photoKey + "."
                );
            }
            if (content != null && !sha256(content).equalsIgnoreCase(contentHash)) {
                throw invalid(
                        sheet,
                        rowIndex,
                        "El contenido de la foto " + photoKey + " está dañado."
                );
            }
            rows.add(new PhotoRow(
                    requiredText(row, columns, "event_key", sheet, rowIndex),
                    requiredText(row, columns, "file_name", sheet, rowIndex),
                    isBackupFormat(formatVersion)
                            ? optionalText(row, columns, "file_path")
                            : requiredText(row, columns, "file_path", sheet, rowIndex),
                    optionalText(row, columns, "mime_type"),
                    optionalText(row, columns, "source_reference"),
                    photoKey,
                    contentHash,
                    content,
                    isBackupFormat(formatVersion)
                            ? requiredText(row, columns, "created_at", sheet, rowIndex)
                            : null
            ));
            if (rows.size() > MAX_PHOTOS) {
                throw new LegacyImportException(
                        "El archivo contiene demasiadas fotos para una sola importación."
                );
            }
        }
        if (isBackupFormat(formatVersion) && !embedded.keySet().equals(usedPhotoKeys)) {
            throw new LegacyImportException(
                    "La copia contiene datos internos de fotos que no están vinculadas."
            );
        }
        return rows;
    }

    private Map<String, byte[]> readPhotoContent(Workbook workbook)
            throws LegacyImportException {
        Sheet sheet = requiredSheet(workbook, "photo_content");
        Map<String, Integer> columns = columns(
                sheet,
                "photo_key",
                "chunk_index",
                "chunk_count",
                "data_base64"
        );
        if (sheet.getPhysicalNumberOfRows() - 1 > MAX_INTERNAL_ROWS) {
            throw new LegacyImportException(
                    "La copia contiene demasiados fragmentos internos de fotos."
            );
        }
        Map<String, ChunkSet> chunksByPhoto = new LinkedHashMap<>();
        long totalBase64Characters = 0;
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (isEmpty(row)) {
                continue;
            }
            String photoKey = requiredText(
                    row,
                    columns,
                    "photo_key",
                    sheet,
                    rowIndex
            );
            int chunkIndex = requiredNonNegativeInteger(
                    row,
                    columns,
                    "chunk_index",
                    sheet,
                    rowIndex
            );
            int chunkCount = requiredNonNegativeInteger(
                    row,
                    columns,
                    "chunk_count",
                    sheet,
                    rowIndex
            );
            if (chunkCount == 0 || chunkIndex >= chunkCount) {
                throw invalid(sheet, rowIndex, "El índice de fragmento no es válido.");
            }
            if (chunkCount > MAX_CHUNKS_PER_PHOTO
                    || chunkCount > Math.max(1, sheet.getPhysicalNumberOfRows() - 1)) {
                throw invalid(
                        sheet,
                        rowIndex,
                        "La foto declara demasiados fragmentos."
                );
            }
            String key = normalizeKey(photoKey);
            ChunkSet chunks = chunksByPhoto.get(key);
            if (chunks == null) {
                if (chunksByPhoto.size() >= MAX_PHOTOS) {
                    throw invalid(sheet, rowIndex, "La copia contiene demasiadas fotos.");
                }
                chunks = new ChunkSet(chunkCount);
                chunksByPhoto.put(key, chunks);
            }
            if (chunks.count() != chunkCount) {
                throw invalid(
                        sheet,
                        rowIndex,
                        "Los fragmentos de " + photoKey + " no coinciden."
                );
            }
            String data = optionalText(row, columns, "data_base64");
            String chunkData = data == null ? "" : data;
            totalBase64Characters += chunkData.length();
            if (totalBase64Characters > MAX_TOTAL_BASE64_CHARACTERS) {
                throw invalid(
                        sheet,
                        rowIndex,
                        "El contenido de fotos supera el máximo admitido."
                );
            }
            if (chunks.characters() + chunkData.length()
                    > MAX_PHOTO_BASE64_CHARACTERS) {
                throw invalid(
                        sheet,
                        rowIndex,
                        "Una de las fotos supera el tamaño máximo admitido."
                );
            }
            if (chunks.put(chunkIndex, chunkData) != null) {
                throw invalid(
                        sheet,
                        rowIndex,
                        "Hay un fragmento repetido para " + photoKey + "."
                );
            }
        }

        Map<String, byte[]> contents = new HashMap<>();
        for (Map.Entry<String, ChunkSet> entry : chunksByPhoto.entrySet()) {
            ChunkSet chunks = entry.getValue();
            if (!chunks.complete()) {
                throw new LegacyImportException(
                        "Faltan fragmentos internos de la foto " + entry.getKey() + "."
                );
            }
            try {
                contents.put(
                        entry.getKey(),
                        Base64.getDecoder().decode(chunks.join())
                );
            } catch (IllegalArgumentException exception) {
                throw new LegacyImportException(
                        "Los datos internos de la foto " + entry.getKey()
                                + " no son válidos.",
                        exception
                );
            }
        }
        return contents;
    }

    private Map<String, String> readTextContent(Workbook workbook)
            throws LegacyImportException {
        Sheet sheet = workbook.getSheet("text_content");
        if (sheet == null) {
            return Map.of();
        }
        Map<String, Integer> columns = rawColumns(
                sheet,
                "text_key",
                "chunk_index",
                "chunk_count",
                "sha256",
                "data"
        );
        if (sheet.getPhysicalNumberOfRows() - 1 > MAX_INTERNAL_ROWS) {
            throw new LegacyImportException(
                    "La copia contiene demasiados fragmentos internos de texto."
            );
        }
        Map<String, TextChunkSet> chunksByText = new LinkedHashMap<>();
        long totalCharacters = 0;
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (rawRowEmpty(row)) {
                continue;
            }
            String key = requiredRawText(
                    row,
                    columns.get("text_key"),
                    sheet,
                    rowIndex,
                    "text_key"
            ).trim();
            int chunkIndex = requiredRawInteger(
                    row,
                    columns.get("chunk_index"),
                    sheet,
                    rowIndex,
                    "chunk_index"
            );
            int chunkCount = requiredRawInteger(
                    row,
                    columns.get("chunk_count"),
                    sheet,
                    rowIndex,
                    "chunk_count"
            );
            String hash = requiredRawText(
                    row,
                    columns.get("sha256"),
                    sheet,
                    rowIndex,
                    "sha256"
            ).trim();
            String data = rawText(row.getCell(columns.get("data")));
            data = data == null ? "" : data;
            if (chunkCount <= 0 || chunkIndex < 0 || chunkIndex >= chunkCount
                    || chunkCount > MAX_TEXT_CHUNKS
                    || chunkCount > Math.max(1, sheet.getPhysicalNumberOfRows() - 1)) {
                throw invalid(sheet, rowIndex, "El fragmento de texto no es válido.");
            }
            TextChunkSet chunks = chunksByText.get(key);
            if (chunks == null) {
                if (chunksByText.size() >= MAX_TEXT_KEYS) {
                    throw invalid(sheet, rowIndex, "La copia contiene demasiados textos.");
                }
                chunks = new TextChunkSet(chunkCount, hash);
                chunksByText.put(key, chunks);
            }
            if (chunks.count() != chunkCount || !chunks.hash().equalsIgnoreCase(hash)) {
                throw invalid(sheet, rowIndex, "Los fragmentos de texto no coinciden.");
            }
            totalCharacters += data.length();
            if (totalCharacters > MAX_TOTAL_TEXT_CHARACTERS
                    || chunks.characters() + data.length() > MAX_TEXT_CHARACTERS) {
                throw invalid(sheet, rowIndex, "El contenido de texto es demasiado grande.");
            }
            if (chunks.put(chunkIndex, data) != null) {
                throw invalid(sheet, rowIndex, "Hay un fragmento de texto repetido.");
            }
        }

        Map<String, String> values = new HashMap<>();
        for (Map.Entry<String, TextChunkSet> entry : chunksByText.entrySet()) {
            TextChunkSet chunks = entry.getValue();
            if (!chunks.complete()) {
                throw new LegacyImportException(
                        "Faltan fragmentos internos del texto " + entry.getKey() + "."
                );
            }
            String value = chunks.join();
            if (!sha256(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .equalsIgnoreCase(chunks.hash())) {
                throw new LegacyImportException(
                        "El texto interno " + entry.getKey() + " está dañado."
                );
            }
            if (values.putIfAbsent(entry.getKey(), value) != null) {
                throw new LegacyImportException(
                        "La clave de texto está repetida: " + entry.getKey() + "."
                );
            }
        }
        return Map.copyOf(values);
    }

    private List<ImportWarning> readWarnings(Workbook workbook)
            throws LegacyImportException {
        Sheet sheet = requiredSheet(workbook, "migration_warnings");
        Map<String, Integer> columns = columns(
                sheet,
                "severity",
                "source",
                "reference",
                "message"
        );
        List<ImportWarning> rows = new ArrayList<>();
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (isEmpty(row)) {
                continue;
            }
            String severity = requiredText(
                    row,
                    columns,
                    "severity",
                    sheet,
                    rowIndex
            ).toUpperCase(Locale.ROOT);
            if (!Set.of("WARN", "REVIEW").contains(severity)) {
                throw invalid(sheet, rowIndex, "Nivel de aviso no válido: " + severity);
            }
            rows.add(new ImportWarning(
                    severity,
                    optionalText(row, columns, "source"),
                    optionalText(row, columns, "reference"),
                    requiredText(row, columns, "message", sheet, rowIndex)
            ));
        }
        return rows;
    }

    private void validateMigratorAudit(
            Workbook workbook,
            Map<String, String> metadata
    ) throws LegacyImportException {
        int unassignedPhotos = validateUnassignedPhotos(workbook);
        int sourceRecords = validateSourceRecords(workbook);

        Sheet audit = requiredSheet(workbook, "migration_audit");
        Map<String, Integer> auditColumns = columns(
                audit,
                "source_name", "source_section", "source_reference", "source_field",
                "original_value", "original_display", "destination",
                "normalized_value", "status", "note"
        );
        Map<String, Integer> statuses = new HashMap<>();
        int auditRecords = 0;
        for (int rowIndex = 1; rowIndex <= audit.getLastRowNum(); rowIndex++) {
            Row row = audit.getRow(rowIndex);
            if (isEmpty(row)) {
                continue;
            }
            requiredNonBlankText(row, auditColumns, "source_name", audit, rowIndex);
            requiredNonBlankText(row, auditColumns, "source_section", audit, rowIndex);
            requiredNonBlankText(row, auditColumns, "source_reference", audit, rowIndex);
            requiredNonBlankText(row, auditColumns, "source_field", audit, rowIndex);
            String status = requiredText(
                    row,
                    auditColumns,
                    "status",
                    audit,
                    rowIndex
            ).toUpperCase(Locale.ROOT);
            if (!Set.of("MIGRATED", "PRESERVED_RAW", "CONFLICT", "LOST", "UNMAPPED")
                    .contains(status)) {
                throw invalid(audit, rowIndex, "Estado de auditoría no válido: " + status);
            }
            statuses.merge(status, 1, Integer::sum);
            auditRecords++;
        }

        validateCount(metadata, "unassigned_photos_count", unassignedPhotos);
        validateCount(metadata, "source_records_count", sourceRecords);
        validateCount(metadata, "audit_records_count", auditRecords);
        validateCount(metadata, "audit_lost_count", statuses.getOrDefault("LOST", 0));
        validateCount(
                metadata,
                "audit_unmapped_count",
                statuses.getOrDefault("UNMAPPED", 0)
        );
        validateCount(
                metadata,
                "audit_conflict_count",
                statuses.getOrDefault("CONFLICT", 0)
        );

        if (statuses.getOrDefault("LOST", 0) > 0
                || statuses.getOrDefault("UNMAPPED", 0) > 0) {
            throw new LegacyImportException(
                    "La migración contiene datos marcados como LOST o UNMAPPED. "
                            + "Corrige la exportación antes de importarla en RingLog."
            );
        }
    }

    private int validateUnassignedPhotos(Workbook workbook)
            throws LegacyImportException {
        Sheet sheet = requiredSheet(workbook, "unassigned_photos");
        Map<String, Integer> columns = columns(
                sheet,
                "file_name", "file_path", "mime_type", "sha256",
                "width", "height", "source_reference"
        );
        int count = 0;
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (isEmpty(row)) {
                continue;
            }
            requiredNonBlankText(row, columns, "file_name", sheet, rowIndex);
            requiredNonBlankText(row, columns, "file_path", sheet, rowIndex);
            requiredNonBlankText(row, columns, "mime_type", sheet, rowIndex);
            String hash = requiredNonBlankText(row, columns, "sha256", sheet, rowIndex);
            if (!hash.matches("(?i)[0-9a-f]{64}")) {
                throw invalid(sheet, rowIndex, "sha256 no contiene un hash válido.");
            }
            requiredNonNegativeInteger(row, columns, "width", sheet, rowIndex);
            requiredNonNegativeInteger(row, columns, "height", sheet, rowIndex);
            requiredNonBlankText(row, columns, "source_reference", sheet, rowIndex);
            count++;
        }
        return count;
    }

    private int validateSourceRecords(Workbook workbook)
            throws LegacyImportException {
        Sheet sheet = requiredSheet(workbook, "source_records");
        Map<String, Integer> columns = columns(
                sheet,
                "source_name", "source_section", "source_reference",
                "ring_number", "raw_payload"
        );
        Set<String> references = new HashSet<>();
        int count = 0;
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (isEmpty(row)) {
                continue;
            }
            requiredNonBlankText(row, columns, "source_name", sheet, rowIndex);
            requiredNonBlankText(row, columns, "source_section", sheet, rowIndex);
            String reference = requiredNonBlankText(
                    row,
                    columns,
                    "source_reference",
                    sheet,
                    rowIndex
            );
            requiredNonBlankText(row, columns, "raw_payload", sheet, rowIndex);
            if (!references.add(reference)) {
                throw invalid(
                        sheet,
                        rowIndex,
                        "La referencia de procedencia está repetida: " + reference
                );
            }
            count++;
        }
        return count;
    }

    private void validateRelationships(
            Path workbook,
            int formatVersion,
            List<SpeciesRow> species,
            List<BirdRow> birds,
            List<PlaceRow> places,
            List<EventRow> events,
            List<PhotoRow> photos
    ) throws LegacyImportException {
        Set<String> speciesKeys = unique(
                species.stream().map(SpeciesRow::key).toList(),
                "species_key"
        );
        Set<String> rings = new HashSet<>();
        for (BirdRow bird : birds) {
            if (!speciesKeys.contains(normalizeKey(bird.speciesKey()))) {
                throw new LegacyImportException(
                        "El ave " + bird.ringNumber() + " referencia una especie inexistente."
                );
            }
            if (!rings.add(normalizeKey(bird.ringNumber()))) {
                throw new LegacyImportException(
                        "La anilla está repetida en el archivo: " + bird.ringNumber()
                );
            }
        }

        Set<String> placeKeys = unique(
                places.stream().map(PlaceRow::key).toList(),
                "place_key"
        );
        if (places.stream().filter(PlaceRow::defaultPlace).count() > 1) {
            throw new LegacyImportException("El archivo contiene más de un lugar predeterminado.");
        }

        Set<String> eventKeys = new HashSet<>();
        Set<String> sourceReferences = new HashSet<>();
        for (EventRow event : events) {
            if (!eventKeys.add(normalizeKey(event.key()))) {
                throw new LegacyImportException(
                        "El event_key está repetido: " + event.key()
                );
            }
            if (!rings.contains(normalizeKey(event.ringNumber()))) {
                throw new LegacyImportException(
                        "El evento " + event.key() + " referencia una anilla inexistente."
                );
            }
            if (event.placeKey() != null
                    && !placeKeys.contains(normalizeKey(event.placeKey()))) {
                throw new LegacyImportException(
                        "El evento " + event.key() + " referencia un lugar inexistente."
                );
            }
            if (event.sourceReference() != null
                    && !sourceReferences.add(event.sourceReference())) {
                throw new LegacyImportException(
                        "La referencia de origen está repetida: " + event.sourceReference()
                );
            }
        }

        Set<String> assignedPhotos = new HashSet<>();
        Path importDirectory = workbook.getParent();
        for (PhotoRow photo : photos) {
            if (!eventKeys.contains(normalizeKey(photo.eventKey()))) {
                throw new LegacyImportException(
                        "La foto " + photo.fileName() + " referencia un evento inexistente."
                );
            }
            String identity;
            if (isBackupFormat(formatVersion)) {
                identity = normalizeKey(photo.eventKey()) + '\u0000'
                        + normalizeKey(photo.photoKey());
            } else {
                Path relative;
                try {
                    relative = Path.of(photo.relativePath());
                } catch (RuntimeException exception) {
                    throw new LegacyImportException(
                            "La ruta de la foto no es válida: " + photo.relativePath(),
                            exception
                    );
                }
                if (relative.isAbsolute()) {
                    throw new LegacyImportException(
                            "La ruta de una foto debe ser relativa al archivo de importación."
                    );
                }
                Path source = importDirectory.resolve(relative).normalize();
                if (!source.startsWith(importDirectory) || !Files.isRegularFile(source)) {
                    throw new LegacyImportException(
                            "No se encuentra la foto vinculada: " + photo.relativePath()
                    );
                }
                identity = normalizeKey(photo.eventKey()) + '\u0000' + source;
            }
            if (!assignedPhotos.add(identity)) {
                throw new LegacyImportException(
                        "La foto está repetida para el mismo evento: " + photo.fileName()
                );
            }
        }
    }

    private Set<String> unique(List<String> values, String column)
            throws LegacyImportException {
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            if (!unique.add(normalizeKey(value))) {
                throw new LegacyImportException(
                        "La clave " + column + " está repetida: " + value
                );
            }
        }
        return unique;
    }

    private boolean isBackupFormat(int formatVersion) {
        return formatVersion == BACKUP_FORMAT_VERSION;
    }

    private List<String> aliases(String value) {
        if (value == null) {
            return List.of();
        }
        return List.of(value.split("\\s*\\|\\s*")).stream()
                .map(String::trim)
                .filter(alias -> !alias.isEmpty())
                .distinct()
                .toList();
    }

    private void validateCount(Map<String, String> metadata, String key, int actual)
            throws LegacyImportException {
        String declared = metadata.get(key);
        if (declared == null) {
            throw new LegacyImportException("Falta el contador " + key + " en metadata.");
        }
        try {
            if (Integer.parseInt(declared) != actual) {
                throw new LegacyImportException(
                        "El contador " + key + " no coincide con el contenido del archivo."
                );
            }
        } catch (NumberFormatException exception) {
            throw new LegacyImportException("El contador " + key + " no es válido.", exception);
        }
    }

    private Map<String, Integer> columns(Sheet sheet, String... required)
            throws LegacyImportException {
        Row header = sheet.getRow(0);
        if (header == null) {
            throw new LegacyImportException("La hoja " + sheet.getSheetName() + " no tiene cabecera.");
        }
        Map<String, Integer> columns = new HashMap<>();
        for (int index = 0; index < header.getLastCellNum(); index++) {
            String name = value(header.getCell(index));
            if (name == null) {
                continue;
            }
            if (columns.putIfAbsent(name, index) != null) {
                throw new LegacyImportException(
                        "La hoja " + sheet.getSheetName() + " repite la columna " + name + "."
                );
            }
        }
        for (String name : required) {
            if (!columns.containsKey(name)) {
                throw new LegacyImportException(
                        "Falta la columna " + name + " en la hoja " + sheet.getSheetName() + "."
                );
            }
        }
        return columns;
    }

    private Sheet requiredSheet(Workbook workbook, String name)
            throws LegacyImportException {
        Sheet sheet = workbook.getSheet(name);
        if (sheet == null) {
            throw new LegacyImportException("Falta la hoja obligatoria: " + name + ".");
        }
        return sheet;
    }

    private boolean isEmpty(Row row) {
        if (row == null) {
            return true;
        }
        for (int index = row.getFirstCellNum(); index < row.getLastCellNum(); index++) {
            if (index >= 0 && value(row.getCell(index)) != null) {
                return false;
            }
        }
        return true;
    }

    private String requiredText(
            Row row,
            Map<String, Integer> columns,
            String column,
            Sheet sheet,
            int rowIndex
    ) throws LegacyImportException {
        String value = optionalText(row, columns, column);
        if (value == null) {
            throw invalid(sheet, rowIndex, "Falta un valor obligatorio en " + column + ".");
        }
        return value;
    }

    private String requiredNonBlankText(
            Row row,
            Map<String, Integer> columns,
            String column,
            Sheet sheet,
            int rowIndex
    ) throws LegacyImportException {
        String value = requiredText(row, columns, column, sheet, rowIndex);
        if (value.isBlank()) {
            throw invalid(sheet, rowIndex, "Falta un valor obligatorio en " + column + ".");
        }
        return value;
    }

    private String optionalText(Row row, Map<String, Integer> columns, String column) {
        return value(row.getCell(columns.get(column)));
    }

    private String value(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        String rawValue = formatter.formatCellValue(cell);
        String trimmed = rawValue.trim();
        if (resolveOverflowReferences && trimmed.startsWith(TEXT_REFERENCE_PREFIX)) {
            String key = trimmed.substring(TEXT_REFERENCE_PREFIX.length());
            String expanded = overflowTexts.get(key);
            if (expanded == null) {
                throw new IllegalArgumentException(
                        "No se encuentra el texto interno " + key + "."
                );
            }
            return expanded;
        }
        String value = preserveTextWhitespace ? rawValue : trimmed;
        return value.isEmpty() ? null : value;
    }

    private Map<String, Integer> rawColumns(Sheet sheet, String... required)
            throws LegacyImportException {
        Row header = sheet.getRow(0);
        if (header == null) {
            throw new LegacyImportException("La hoja " + sheet.getSheetName()
                    + " no tiene cabecera.");
        }
        Map<String, Integer> columns = new HashMap<>();
        for (int index = 0; index < header.getLastCellNum(); index++) {
            String name = rawText(header.getCell(index));
            if (name != null) {
                String normalized = name.trim();
                if (columns.putIfAbsent(normalized, index) != null) {
                    throw new LegacyImportException(
                            "La hoja " + sheet.getSheetName()
                                    + " repite la columna " + normalized + "."
                    );
                }
            }
        }
        for (String name : required) {
            if (!columns.containsKey(name)) {
                throw new LegacyImportException(
                        "Falta la columna " + name + " en la hoja "
                                + sheet.getSheetName() + "."
                );
            }
        }
        return columns;
    }

    private String requiredRawText(
            Row row,
            int column,
            Sheet sheet,
            int rowIndex,
            String name
    ) throws LegacyImportException {
        String value = rawText(row.getCell(column));
        if (value == null || value.isEmpty()) {
            throw invalid(sheet, rowIndex, "Falta un valor obligatorio en " + name + ".");
        }
        return value;
    }

    private int requiredRawInteger(
            Row row,
            int column,
            Sheet sheet,
            int rowIndex,
            String name
    ) throws LegacyImportException {
        String value = requiredRawText(row, column, sheet, rowIndex, name).trim();
        try {
            double number = Double.parseDouble(value);
            if (number != Math.rint(number) || number < 0 || number > Integer.MAX_VALUE) {
                throw new NumberFormatException("not an integer");
            }
            return (int) number;
        } catch (NumberFormatException exception) {
            throw invalid(sheet, rowIndex, name + " no es un entero válido.", exception);
        }
    }

    private boolean rawRowEmpty(Row row) {
        if (row == null) {
            return true;
        }
        for (Cell cell : row) {
            String value = rawText(cell);
            if (value != null && !value.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private String rawText(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() == CellType.STRING) {
            return cell.getStringCellValue();
        }
        return formatter.formatCellValue(cell);
    }

    private String requiredDate(
            Row row,
            Map<String, Integer> columns,
            String column,
            Sheet sheet,
            int rowIndex
    ) throws LegacyImportException {
        Cell cell = row.getCell(columns.get(column));
        try {
            if (isNumeric(cell)) {
                return DateUtil.getLocalDateTime(cell.getNumericCellValue())
                        .toLocalDate()
                        .toString();
            }
            String value = requiredText(row, columns, column, sheet, rowIndex);
            for (DateTimeFormatter dateFormat : List.of(
                    DateTimeFormatter.ISO_LOCAL_DATE,
                    DateTimeFormatter.ofPattern("d/M/uuuu", Locale.ROOT)
            )) {
                try {
                    return LocalDate.parse(value, dateFormat).toString();
                } catch (DateTimeParseException ignored) {
                    // Try the next accepted representation.
                }
            }
        } catch (RuntimeException exception) {
            throw invalid(sheet, rowIndex, "La fecha no es válida.", exception);
        }
        throw invalid(sheet, rowIndex, "La fecha no es válida.");
    }

    private String optionalTime(
            Row row,
            Map<String, Integer> columns,
            String column,
            Sheet sheet,
            int rowIndex
    ) throws LegacyImportException {
        Cell cell = row.getCell(columns.get(column));
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        try {
            LocalTime time;
            if (isNumeric(cell)) {
                time = DateUtil.getLocalDateTime(cell.getNumericCellValue()).toLocalTime();
            } else {
                String value = optionalText(row, columns, column);
                if (value == null) {
                    return null;
                }
                time = LocalTime.parse(value, FLEXIBLE_TIME);
            }
            time = time.withNano(0);
            return time.getSecond() == 0
                    ? time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))
                    : time.format(DateTimeFormatter.ISO_LOCAL_TIME);
        } catch (RuntimeException exception) {
            throw invalid(sheet, rowIndex, "La hora no es válida.", exception);
        }
    }

    private Integer optionalInteger(
            Row row,
            Map<String, Integer> columns,
            String column,
            Sheet sheet,
            int rowIndex
    ) throws LegacyImportException {
        Double value = optionalDouble(row, columns, column, sheet, rowIndex);
        if (value == null) {
            return null;
        }
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw invalid(sheet, rowIndex, column + " debe ser un número entero.");
        }
        return value.intValue();
    }

    private int requiredNonNegativeInteger(
            Row row,
            Map<String, Integer> columns,
            String column,
            Sheet sheet,
            int rowIndex
    ) throws LegacyImportException {
        Integer value = optionalInteger(row, columns, column, sheet, rowIndex);
        if (value == null || value < 0) {
            throw invalid(sheet, rowIndex, column + " debe ser un entero positivo.");
        }
        return value;
    }

    private Double nonNegative(
            Row row,
            Map<String, Integer> columns,
            String column,
            Sheet sheet,
            int rowIndex
    ) throws LegacyImportException {
        Double value = optionalDouble(row, columns, column, sheet, rowIndex);
        if (value != null && value < 0) {
            throw invalid(sheet, rowIndex, column + " no puede ser negativo.");
        }
        return value;
    }

    private Double optionalDouble(
            Row row,
            Map<String, Integer> columns,
            String column,
            Sheet sheet,
            int rowIndex
    ) throws LegacyImportException {
        Cell cell = row.getCell(columns.get(column));
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        try {
            double number;
            if (isNumeric(cell)) {
                number = cell.getNumericCellValue();
            } else {
                String value = optionalText(row, columns, column);
                if (value == null) {
                    return null;
                }
                number = Double.parseDouble(value.replace(',', '.'));
            }
            if (!Double.isFinite(number)) {
                throw new NumberFormatException("non-finite");
            }
            return number;
        } catch (RuntimeException exception) {
            throw invalid(sheet, rowIndex, column + " no contiene un número válido.", exception);
        }
    }

    private boolean requiredBoolean(
            Row row,
            Map<String, Integer> columns,
            String column,
            Sheet sheet,
            int rowIndex
    ) throws LegacyImportException {
        Cell cell = row.getCell(columns.get(column));
        if (cell != null) {
            if (cell.getCellType() == CellType.BOOLEAN) {
                return cell.getBooleanCellValue();
            }
            if (isNumeric(cell)) {
                double number = cell.getNumericCellValue();
                if (number == 0) {
                    return false;
                }
                if (number == 1) {
                    return true;
                }
            }
        }
        String value = optionalText(row, columns, column);
        if (value != null) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "1", "true", "sí", "si", "yes" -> true;
                case "0", "false", "no" -> false;
                default -> throw invalid(
                        sheet,
                        rowIndex,
                        column + " debe contener 1 o 0."
                );
            };
        }
        throw invalid(sheet, rowIndex, "Falta un valor obligatorio en " + column + ".");
    }

    private boolean isNumeric(Cell cell) {
        if (cell == null) {
            return false;
        }
        return cell.getCellType() == CellType.NUMERIC;
    }

    private LegacyImportException invalid(Sheet sheet, int rowIndex, String message) {
        return invalid(sheet, rowIndex, message, null);
    }

    private LegacyImportException invalid(
            Sheet sheet,
            int rowIndex,
            String message,
            Throwable cause
    ) {
        String contextual = "Hoja " + sheet.getSheetName() + ", fila "
                + (rowIndex + 1) + ": " + message;
        return cause == null
                ? new LegacyImportException(contextual)
                : new LegacyImportException(contextual, cause);
    }

    private static String normalizeKey(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private void rejectFormulas(Workbook workbook) throws LegacyImportException {
        for (Sheet sheet : workbook) {
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell.getCellType() == CellType.FORMULA) {
                        throw invalid(
                                sheet,
                                row.getRowNum(),
                                "No se admiten fórmulas en archivos de importación."
                        );
                    }
                }
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                value.append(Character.forDigit((item >>> 4) & 0x0f, 16));
                value.append(Character.forDigit(item & 0x0f, 16));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible.", exception);
        }
    }

    private static final class ChunkSet {

        private final int count;
        private final Map<Integer, String> chunks = new HashMap<>();
        private long characters;

        private ChunkSet(int count) {
            this.count = count;
        }

        private int count() {
            return count;
        }

        private long characters() {
            return characters;
        }

        private String put(int index, String value) {
            String previous = chunks.put(index, value);
            if (previous == null) {
                characters += value.length();
            }
            return previous;
        }

        private boolean complete() {
            if (chunks.size() != count) {
                return false;
            }
            for (int index = 0; index < count; index++) {
                if (!chunks.containsKey(index)) {
                    return false;
                }
            }
            return true;
        }

        private String join() {
            StringBuilder value = new StringBuilder(Math.toIntExact(characters));
            for (int index = 0; index < count; index++) {
                value.append(chunks.get(index));
            }
            return value.toString();
        }
    }

    private static final class TextChunkSet {

        private final int count;
        private final String hash;
        private final Map<Integer, String> chunks = new HashMap<>();
        private long characters;

        private TextChunkSet(int count, String hash) {
            this.count = count;
            this.hash = hash;
        }

        private int count() {
            return count;
        }

        private String hash() {
            return hash;
        }

        private long characters() {
            return characters;
        }

        private String put(int index, String value) {
            String previous = chunks.put(index, value);
            if (previous == null) {
                characters += value.length();
            }
            return previous;
        }

        private boolean complete() {
            if (chunks.size() != count) {
                return false;
            }
            for (int index = 0; index < count; index++) {
                if (!chunks.containsKey(index)) {
                    return false;
                }
            }
            return true;
        }

        private String join() {
            StringBuilder value = new StringBuilder(Math.toIntExact(characters));
            for (int index = 0; index < count; index++) {
                value.append(chunks.get(index));
            }
            return value.toString();
        }
    }
}
