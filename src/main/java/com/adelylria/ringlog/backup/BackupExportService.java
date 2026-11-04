package com.adelylria.ringlog.backup;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.RichTextString;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.model.SharedStringsTable;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.streaming.SheetDataWriter;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.importer.ImportPreview;
import com.adelylria.ringlog.importer.LegacyImportException;
import com.adelylria.ringlog.importer.LegacyImportService;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.MediaPathResolver;

public final class BackupExportService {

    private static final int BASE64_CHUNK_SIZE = 30_000;
    private static final int BASE64_CHUNK_BYTES = (BASE64_CHUNK_SIZE / 4) * 3;
    private static final int TEXT_CHUNK_SIZE = 30_000;
    private static final int MAX_EXCEL_ROWS = 1_048_576;
    private static final int MAX_PHOTOS = 5_000;
    private static final long MAX_PHOTO_BASE64_CHARACTERS = 30_000_000L;
    private static final long MAX_TOTAL_BASE64_CHARACTERS = 80_000_000L;
    private static final long MAX_PHOTO_BYTES =
            (MAX_PHOTO_BASE64_CHARACTERS / 4L) * 3L;
    private static final long MAX_TOTAL_PHOTO_BYTES =
            (MAX_TOTAL_BASE64_CHARACTERS / 4L) * 3L;
    private static final long MAX_TEXT_CHARACTERS = 5_000_000L;
    private static final long MAX_TOTAL_TEXT_CHARACTERS = 20_000_000L;
    private static final String TEXT_REFERENCE_PREFIX = "@@RINGLOG_TEXT:";
    private static final String[] SPECIES_HEADERS = {
            "species_key", "code", "scientific_name", "common_name", "active",
            "created_at", "updated_at"
    };
    private static final String[] BIRD_HEADERS = {
            "ring_number", "species_key", "created_at", "updated_at"
    };
    private static final String[] PLACE_HEADERS = {
            "place_key", "name", "locality", "latitude", "longitude", "notes",
            "is_favorite", "is_default", "active", "created_at", "updated_at"
    };
    private static final String[] EVENT_HEADERS = {
            "event_key", "ring_number", "event_type", "event_date", "event_time",
            "place_key", "location_text", "sex_code", "age_euring_code",
            "fat_score", "muscle_score", "ringer_initials", "status",
            "reproductive_status", "moult_intensity", "moult_extension",
            "bird_condition", "return_status", "wing", "p3", "torso", "weight",
            "observations", "clouds", "rain", "thermal_sensation", "wind",
            "capture_type", "is_dead", "source_name", "source_reference",
            "source_aliases", "created_at", "updated_at", "capture_method_stable_key"
    };
    private static final String[] CAPTURE_METHOD_HEADERS = {"stable_key", "name", "active"};
    private static final String[] PHOTO_HEADERS = {
            "event_key", "file_name", "file_path", "mime_type", "source_reference",
            "photo_key", "sha256", "created_at"
    };
    private static final String[] PHOTO_CONTENT_HEADERS = {
            "photo_key", "chunk_index", "chunk_count", "data_base64"
    };
    private static final String[] WARNING_HEADERS = {
            "severity", "source", "reference", "message"
    };

    private final String databaseFile;
    private final MediaPathResolver mediaResolver;

    public BackupExportService() {
        this(AppPaths.production());
    }

    public BackupExportService(AppPaths paths) {
        this(paths.databasePath().toString(), new MediaPathResolver(paths));
    }

    public BackupExportService(String databaseFile) {
        this(
                databaseFile,
                new MediaPathResolver(AppPaths.forDatabase(Path.of(databaseFile)))
        );
    }

    public BackupExportService(String databaseFile, MediaPathResolver mediaResolver) {
        if (databaseFile == null || databaseFile.isBlank()) {
            throw new IllegalArgumentException("Indica la base de datos de RingLog.");
        }
        this.databaseFile = Path.of(databaseFile)
                .toAbsolutePath()
                .normalize()
                .toString();
        this.mediaResolver = java.util.Objects.requireNonNull(mediaResolver, "mediaResolver");
    }

    public BackupExportResult exportTo(Path destination)
            throws BackupExportException {
        if (destination == null) {
            throw new BackupExportException("Selecciona dónde guardar la copia.");
        }
        Path output = xlsxPath(destination.toAbsolutePath().normalize());
        Path temporary = null;
        try {
            Database.validate(databaseFile);
            PreparedBackup prepared = prepareBackup(output);
            temporary = prepared.file();
            BackupCounts counts = prepared.counts();
            validateWrittenWorkbook(temporary, counts);
            replaceAtomically(temporary, output);
            temporary = null;
            return new BackupExportResult(
                    output,
                    counts.species(),
                    counts.birds(),
                    counts.places(),
                    counts.events(),
                    counts.photos()
            );
        } catch (BackupExportException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new BackupExportException(
                    "No se pudo leer la base de datos para crear la copia.",
                    exception
            );
        } catch (IOException | RuntimeException exception) {
            throw new BackupExportException(
                    exception.getMessage() == null || exception.getMessage().isBlank()
                            ? "No se pudo guardar la copia de seguridad."
                            : "No se pudo guardar la copia: " + exception.getMessage(),
                    exception
            );
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The original export error remains the useful one for the user.
                }
            }
        }
    }

    private PreparedBackup prepareBackup(Path output)
            throws SQLException, IOException, BackupExportException {
        BackupData data = readBackupData();
        Path temporary = null;
        try {
            Files.createDirectories(output.getParent());
            temporary = Files.createTempFile(output.getParent(), "." + output.getFileName() + ".", ".tmp");
            writeWorkbook(temporary, data);
            BackupCounts counts = new BackupCounts(data.species().size(), data.birds().size(),
                    data.places().size(), data.events().size(), data.photos().size());
            PreparedBackup prepared = new PreparedBackup(temporary, counts);
            temporary = null;
            // Returning only the path and counts releases the snapshot, including photo bytes,
            // before validation opens and materializes a second representation of the workbook.
            return prepared;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Preserve the original write failure.
                }
            }
        }
    }

    private BackupData readBackupData()
            throws SQLException, IOException, BackupExportException {
        try (Connection connection = Database.getConnection(databaseFile)) {
            connection.setAutoCommit(false);
            try {
                List<Object[]> species = readSpecies(connection);
                List<Object[]> birds = readBirds(connection);
                List<Object[]> places = readPlaces(connection);
                List<Object[]> captureMethods = readCaptureMethods(connection);
                EventExport events = readEvents(connection);
                List<PhotoExport> photos = readPhotos(
                        connection,
                        events.keys(),
                        events.references()
                );
                connection.rollback();
                return new BackupData(species, birds, places, events.rows(), photos, captureMethods);
            } catch (SQLException | IOException | BackupExportException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    private List<Object[]> readSpecies(Connection connection) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT stable_key, code, scientific_name, common_name, active,
                            created_at, updated_at
                     FROM species
                     ORDER BY id
                     """)) {
            while (result.next()) {
                rows.add(new Object[]{
                        result.getString("stable_key"),
                        result.getString("code"),
                        result.getString("scientific_name"),
                        result.getString("common_name"),
                        result.getInt("active"),
                        result.getString("created_at"),
                        result.getString("updated_at")
                });
            }
        }
        return List.copyOf(rows);
    }

    private List<Object[]> readBirds(Connection connection) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT b.ring_number, s.stable_key AS species_stable_key,
                            b.created_at, b.updated_at
                     FROM bird b
                     JOIN species s ON s.id = b.species_id
                     ORDER BY b.id
                     """)) {
            while (result.next()) {
                rows.add(new Object[]{
                        result.getString("ring_number"),
                        result.getString("species_stable_key"),
                        result.getString("created_at"),
                        result.getString("updated_at")
                });
            }
        }
        return List.copyOf(rows);
    }

    private List<Object[]> readPlaces(Connection connection) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT stable_key, name, locality, latitude, longitude, notes,
                            is_favorite, is_default, active, created_at, updated_at
                     FROM place
                     ORDER BY id
                     """)) {
            while (result.next()) {
                rows.add(new Object[]{
                        result.getString("stable_key"),
                        result.getString("name"),
                        result.getString("locality"),
                        nullableDouble(result, "latitude"),
                        nullableDouble(result, "longitude"),
                        result.getString("notes"),
                        result.getInt("is_favorite"),
                        result.getInt("is_default"),
                        result.getInt("active"),
                        result.getString("created_at"),
                        result.getString("updated_at")
                });
            }
        }
        return List.copyOf(rows);
    }

    private List<Object[]> readCaptureMethods(Connection connection) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT stable_key, name, active FROM capture_method ORDER BY stable_key")) {
            while (result.next()) {
                rows.add(new Object[]{result.getString(1), result.getString(2), result.getInt(3)});
            }
        }
        return List.copyOf(rows);
    }

    private EventExport readEvents(Connection connection) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        Map<Long, String> keys = new HashMap<>();
        Map<Long, String> references = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT e.*, b.ring_number, p.stable_key AS place_stable_key,
                            cm.stable_key AS capture_method_stable_key
                     FROM bird_event e
                     JOIN bird b ON b.id = e.bird_id
                     LEFT JOIN place p ON p.id = e.place_id
                     LEFT JOIN capture_method cm ON cm.id = e.capture_method_id
                     ORDER BY e.id
                     """)) {
            while (result.next()) {
                long id = result.getLong("id");
                String key = result.getString("stable_key");
                String reference = result.getString("source_reference");
                String sourceName = result.getString("source_name");
                keys.put(id, key);
                references.put(
                        id,
                        reference == null || reference.isBlank()
                                ? stableEventReference(result)
                                : reference
                );
                rows.add(new Object[]{
                        key,
                        result.getString("ring_number"),
                        result.getString("event_type"),
                        result.getString("event_date"),
                        result.getString("event_time"),
                        result.getString("place_stable_key"),
                        result.getString("location_text"),
                        result.getString("sex_code"),
                        result.getString("age_euring_code"),
                        nullableInteger(result, "fat_score"),
                        nullableInteger(result, "muscle_score"),
                        result.getString("ringer_initials"),
                        result.getString("status"),
                        result.getString("reproductive_status"),
                        result.getString("moult_intensity"),
                        result.getString("moult_extension"),
                        result.getString("bird_condition"),
                        result.getString("return_status"),
                        nullableDouble(result, "wing"),
                        nullableDouble(result, "p3"),
                        nullableDouble(result, "torso"),
                        nullableDouble(result, "weight"),
                        result.getString("observations"),
                        result.getString("clouds"),
                        result.getString("rain"),
                        result.getString("thermal_sensation"),
                        result.getString("wind"),
                        result.getString("capture_type"),
                        result.getInt("is_dead"),
                        sourceName,
                        reference,
                        null,
                        result.getString("created_at"),
                        result.getString("updated_at"),
                        result.getString("capture_method_stable_key")
                });
            }
        }
        return new EventExport(List.copyOf(rows), Map.copyOf(keys), Map.copyOf(references));
    }

    private List<PhotoExport> readPhotos(
            Connection connection,
            Map<Long, String> eventKeys,
            Map<Long, String> eventReferences
    ) throws SQLException, IOException, BackupExportException {
        List<PhotoExport> photos = new ArrayList<>();
        long totalPhotoBytes = 0;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT stable_key, event_id, file_name, file_path, mime_type,
                            source_reference, created_at
                     FROM event_photo
                     ORDER BY id
                     """)) {
            while (result.next()) {
                if (photos.size() >= MAX_PHOTOS) {
                    throw new BackupExportException(
                            "Hay demasiadas fotos para guardarlas en un único Excel."
                    );
                }
                long eventId = result.getLong("event_id");
                String eventKey = eventKeys.get(eventId);
                if (eventKey == null) {
                    throw new BackupExportException(
                            "Hay una foto vinculada a un evento que ya no existe."
                    );
                }
                Path source;
                try {
                    source = mediaResolver.resolveEventPhoto(result.getString("file_path"));
                } catch (RuntimeException exception) {
                    throw new BackupExportException(
                            "La ruta de la foto " + result.getString("file_name")
                                    + " no es válida.",
                            exception
                    );
                }
                if (!Files.isRegularFile(source)) {
                    throw new BackupExportException(
                            "No se encuentra la foto vinculada "
                                    + result.getString("file_name")
                                    + ". Corrige o elimina esa referencia antes de exportar."
                    );
                }
                long declaredSize = Files.size(source);
                if (declaredSize > MAX_PHOTO_BYTES
                        || totalPhotoBytes > MAX_TOTAL_PHOTO_BYTES - declaredSize) {
                    throw new BackupExportException(
                            "Las fotos superan el tamaño máximo de una copia en Excel."
                    );
                }
                byte[] content = readPhotoBytes(source, declaredSize);
                if (content.length != declaredSize) {
                    throw new BackupExportException(
                            "La foto " + result.getString("file_name")
                                    + " cambió mientras se preparaba la copia."
                    );
                }
                totalPhotoBytes += content.length;
                String contentHash = sha256(content);
                String photoKey = result.getString("stable_key");
                String sourceReference = result.getString("source_reference");
                if (sourceReference == null || sourceReference.isBlank()) {
                    sourceReference = "RINGLOG:PHOTO:" + sha256((
                            eventReferences.get(eventId) + '\u0000'
                                    + photoKey + '\u0000'
                                    + result.getString("created_at") + '\u0000'
                                    + result.getString("file_name") + '\u0000'
                                    + contentHash
                    ).getBytes(StandardCharsets.UTF_8));
                }
                photos.add(new PhotoExport(
                        eventKey,
                        result.getString("file_name"),
                        result.getString("mime_type"),
                        sourceReference,
                        photoKey,
                        contentHash,
                        result.getString("created_at"),
                        content
                ));
            }
        }
        return List.copyOf(photos);
    }

    private static byte[] readPhotoBytes(Path source, long expectedSize)
            throws IOException {
        if (expectedSize < 0 || expectedSize > MAX_PHOTO_BYTES) {
            throw new IOException("La foto supera el tamaño máximo de una copia en Excel.");
        }
        byte[] content = new byte[Math.toIntExact(expectedSize)];
        try (InputStream input = Files.newInputStream(source)) {
            int total = input.readNBytes(content, 0, content.length);
            if (total != content.length || input.read() != -1) {
                throw new IOException("La foto cambió mientras se preparaba la copia.");
            }
        }
        return content;
    }

    private void writeWorkbook(Path destination, BackupData data)
            throws IOException, BackupExportException {
        // A bounded row window avoids retaining every workbook cell in memory.
        // POI closes/disposes the temporary sheet files on both success and failure.
        try (SXSSFWorkbook workbook = new BackupWorkbook()) {
            WorkbookStyles styles = new WorkbookStyles(workbook);
            TextStore textStore = new TextStore();
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("format", "RingLog Import");
            metadata.put("format_version", "3");
            metadata.put("backup_kind", "RingLog full backup");
            metadata.put("generated_at", Instant.now().toString());
            metadata.put("species_count", Integer.toString(data.species().size()));
            metadata.put("birds_count", Integer.toString(data.birds().size()));
            metadata.put("places_count", Integer.toString(data.places().size()));
            metadata.put("events_count", Integer.toString(data.events().size()));
            metadata.put("capture_methods_count", Integer.toString(data.captureMethods().size()));
            metadata.put("photos_count", Integer.toString(data.photos().size()));
            metadata.put("warnings_count", "0");
            List<Object[]> metadataRows = metadata.entrySet().stream()
                    .map(entry -> new Object[]{entry.getKey(), entry.getValue()})
                    .toList();

            writeSheet(workbook, styles, textStore, "metadata",
                    new String[]{"key", "value"}, metadataRows);
            writeSheet(workbook, styles, textStore, "species", SPECIES_HEADERS,
                    data.species());
            writeSheet(workbook, styles, textStore, "birds", BIRD_HEADERS,
                    data.birds());
            writeSheet(workbook, styles, textStore, "places", PLACE_HEADERS,
                    data.places());
            writeSheet(workbook, styles, textStore, "capture_methods", CAPTURE_METHOD_HEADERS,
                    data.captureMethods());
            writeSheet(workbook, styles, textStore, "events", EVENT_HEADERS,
                    data.events());
            writeSheet(workbook, styles, textStore, "photos", PHOTO_HEADERS,
                    photoRows(data.photos()));
            writeSheet(workbook, styles, textStore, "migration_warnings",
                    WARNING_HEADERS, List.of());
            writePhotoContent(workbook, styles, data.photos());
            writeTextContent(workbook, styles, textStore);

            int contentIndex = workbook.getSheetIndex("photo_content");
            workbook.setSheetHidden(contentIndex, true);
            workbook.setSheetHidden(workbook.getSheetIndex("text_content"), true);
            workbook.setActiveSheet(workbook.getSheetIndex("metadata"));
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(destination), 64 * 1024)) {
                workbook.write(output);
            }
        }
    }

    private List<Object[]> photoRows(List<PhotoExport> photos) {
        return photos.stream().map(photo -> new Object[]{
                photo.eventKey(),
                photo.fileName(),
                null,
                photo.mimeType(),
                photo.sourceReference(),
                photo.photoKey(),
                photo.contentHash(),
                photo.createdAt()
        }).toList();
    }

    private void writePhotoContent(
            Workbook workbook,
            WorkbookStyles styles,
            List<PhotoExport> photos
    ) throws BackupExportException {
        Sheet sheet = workbook.createSheet("photo_content");
        writeHeader(sheet, styles, PHOTO_CONTENT_HEADERS);
        int rowIndex = 1;
        long totalBase64Characters = 0;
        for (PhotoExport photo : photos) {
            byte[] content = photo.content();
            long encodedLength = 4L * ((content.length + 2L) / 3L);
            totalBase64Characters += encodedLength;
            if (encodedLength > MAX_PHOTO_BASE64_CHARACTERS
                    || totalBase64Characters > MAX_TOTAL_BASE64_CHARACTERS) {
                throw new BackupExportException(
                        "Las fotos superan el tamaño máximo de una copia en Excel."
                );
            }
            int chunks = Math.max(1, Math.toIntExact((encodedLength + BASE64_CHUNK_SIZE - 1)
                    / BASE64_CHUNK_SIZE));
            if ((long) rowIndex + chunks > MAX_EXCEL_ROWS) {
                throw new BackupExportException(
                        "Las fotos son demasiado grandes para guardarlas en un único Excel."
                );
            }
            for (int chunk = 0; chunk < chunks; chunk++) {
                int start = chunk * BASE64_CHUNK_BYTES;
                int end = Math.min(content.length, start + BASE64_CHUNK_BYTES);
                // A full byte chunk is divisible by three, so concatenation has exactly the
                // same padding and 30,000-character boundaries as encoding the entire image.
                String base64 = Base64.getEncoder().encodeToString(Arrays.copyOfRange(content, start, end));
                Row row = sheet.createRow(rowIndex++);
                setRawCell(row, 0, photo.photoKey(), styles.body());
                setRawCell(row, 1, chunk, styles.body());
                setRawCell(row, 2, chunks, styles.body());
                setRawCell(row, 3, base64, styles.body());
            }
        }
    }

    private void writeTextContent(
            Workbook workbook,
            WorkbookStyles styles,
            TextStore textStore
    ) throws BackupExportException {
        Sheet sheet = workbook.createSheet("text_content");
        writeHeader(
                sheet,
                styles,
                new String[]{"text_key", "chunk_index", "chunk_count", "sha256", "data"}
        );
        int rowIndex = 1;
        long totalCharacters = 0;
        for (Map.Entry<String, String> entry : textStore.values().entrySet()) {
            String value = entry.getValue();
            totalCharacters += value.length();
            if (value.length() > MAX_TEXT_CHARACTERS
                    || totalCharacters > MAX_TOTAL_TEXT_CHARACTERS) {
                throw new BackupExportException(
                        "Los textos superan el tamaño máximo de una copia en Excel."
                );
            }
            List<String> chunks = unicodeChunks(value, TEXT_CHUNK_SIZE);
            if ((long) rowIndex + chunks.size() > MAX_EXCEL_ROWS) {
                throw new BackupExportException(
                        "Los textos son demasiado grandes para guardarlos en un único Excel."
                );
            }
            String contentHash = sha256(value.getBytes(StandardCharsets.UTF_8));
            for (int chunk = 0; chunk < chunks.size(); chunk++) {
                Row row = sheet.createRow(rowIndex++);
                setRawCell(row, 0, entry.getKey(), styles.body());
                setRawCell(row, 1, chunk, styles.body());
                setRawCell(row, 2, chunks.size(), styles.body());
                setRawCell(row, 3, contentHash, styles.body());
                setRawCell(row, 4, chunks.get(chunk), styles.body());
            }
        }
    }

    private static List<String> unicodeChunks(String value, int maximumLength) {
        if (value.isEmpty()) {
            return List.of("");
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < value.length()) {
            int end = Math.min(value.length(), start + maximumLength);
            if (end < value.length()
                    && Character.isHighSurrogate(value.charAt(end - 1))
                    && Character.isLowSurrogate(value.charAt(end))) {
                end--;
            }
            chunks.add(value.substring(start, end));
            start = end;
        }
        return List.copyOf(chunks);
    }

    private void writeSheet(
            Workbook workbook,
            WorkbookStyles styles,
            TextStore textStore,
            String name,
            String[] headers,
            List<Object[]> rows
    ) {
        Sheet sheet = workbook.createSheet(name);
        writeHeader(sheet, styles, headers);
        ColumnWidths widths = new ColumnWidths(headers);
        int rowIndex = 1;
        for (Object[] values : rows) {
            Row row = sheet.createRow(rowIndex++);
            for (int column = 0; column < values.length; column++) {
                Object stored = setCell(row, column, values[column], styles.body(), textStore);
                widths.include(column, stored);
            }
        }
        sheet.createFreezePane(0, 1);
        if (headers.length > 0) {
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                    0,
                    Math.max(0, rows.size()),
                    0,
                    headers.length - 1
            ));
        }
        widths.apply(sheet);
    }

    private void writeHeader(Sheet sheet, WorkbookStyles styles, String[] headers) {
        Row header = sheet.createRow(0);
        header.setHeightInPoints(24f);
        for (int column = 0; column < headers.length; column++) {
            setRawCell(header, column, headers[column], styles.header());
        }
    }

    private Object setCell(
            Row row,
            int column,
            Object value,
            CellStyle style,
            TextStore textStore
    ) {
        Object stored = value;
        if (value instanceof String string
                && (string.isEmpty()
                || string.length() > TEXT_CHUNK_SIZE
                || string.startsWith(TEXT_REFERENCE_PREFIX))) {
            stored = textStore.reference(string);
        }
        setRawCell(row, column, stored, style);
        return stored;
    }

    private void setRawCell(Row row, int column, Object value, CellStyle style) {
        Cell cell = row.createCell(column);
        if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else if (value != null) {
            cell.setCellValue(value.toString());
        }
        cell.setCellStyle(style);
    }

    private void validateWrittenWorkbook(Path workbookFile, BackupCounts expected)
            throws BackupExportException {
        try {
            ImportPreview preview = new LegacyImportService(
                    databaseFile,
                    workbookFile.getParent().resolve(".ringlog-backup-validation")
            ).preview(workbookFile);
            if (preview.speciesCount() != expected.species()
                    || preview.birdsCount() != expected.birds()
                    || preview.placesCount() != expected.places()
                    || preview.eventsCount() != expected.events()
                    || preview.photosCount() != expected.photos()) {
                throw new BackupExportException(
                        "La copia generada no coincide con los datos de RingLog."
                );
            }
        } catch (LegacyImportException exception) {
            throw new BackupExportException(
                    "No se pudo verificar la copia generada.",
                    exception
            );
        }
    }

    private static Path xlsxPath(Path destination) {
        String name = destination.getFileName().toString();
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) {
            return destination;
        }
        return destination.resolveSibling(name + ".xlsx");
    }

    private static void replaceAtomically(Path source, Path destination)
            throws IOException {
        try {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException(
                    "La ubicación elegida no permite sustituir la copia de forma segura.",
                    exception
            );
        }
    }

    private static String stableEventReference(ResultSet result) throws SQLException {
        String identity = result.getString("stable_key") + "\u0000"
                + text(result.getString("created_at")) + "\u0000"
                + text(result.getString("ring_number")) + "\u0000"
                + text(result.getString("event_type")) + "\u0000"
                + text(result.getString("event_date")) + "\u0000"
                + text(result.getString("event_time"));
        return "RINGLOG:EVENT:" + sha256(identity.getBytes(StandardCharsets.UTF_8));
    }

    private static Integer nullableInteger(ResultSet result, String column)
            throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet result, String column)
            throws SQLException {
        double value = result.getDouble(column);
        return result.wasNull() ? null : value;
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
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

    private record BackupData(
            List<Object[]> species,
            List<Object[]> birds,
            List<Object[]> places,
            List<Object[]> events,
            List<PhotoExport> photos,
            List<Object[]> captureMethods
    ) {
    }

    private record BackupCounts(int species, int birds, int places, int events, int photos) {
    }

    private record PreparedBackup(Path file, BackupCounts counts) {
    }

    private record EventExport(
            List<Object[]> rows,
            Map<Long, String> keys,
            Map<Long, String> references
    ) {
    }

    private record PhotoExport(
            String eventKey,
            String fileName,
            String mimeType,
            String sourceReference,
            String photoKey,
            String contentHash,
            String createdAt,
            byte[] content
    ) {
        // This private snapshot owns the freshly read bytes; they never escape the exporter
        // and are never mutated. Defensive copies here would duplicate the entire photo set.
    }

    private static final class BackupWorkbook extends SXSSFWorkbook {
        private BackupWorkbook() {
            super(null, 128, false, true);
            setZip64Mode(Zip64Mode.AsNeeded);
        }

        @Override
        protected SheetDataWriter createSheetDataWriter() throws IOException {
            SharedStringsTable sharedStrings = getXSSFWorkbook().getSharedStringSource();
            return new SheetDataWriter(new SharedStringsTable() {
                @Override
                public int addSharedStringItem(RichTextString value) {
                    // All exporter cells use plain strings. getString() would decode literal
                    // _xNNNN_ input before we can protect it; encode the original XML text.
                    String raw = ((XSSFRichTextString) value).getCTRst().getT();
                    return sharedStrings.addSharedStringItem(new XSSFRichTextString(spreadsheetText(raw)));
                }
            });
        }

        @Override
        @SuppressWarnings("deprecation") // Commons Compress exposes the stream's shared deflater here.
        protected ZipArchiveOutputStream createArchiveOutputStream(OutputStream output) {
            ZipArchiveOutputStream archive = new ZipArchiveOutputStream(output) {
                @Override
                public void putArchiveEntry(ZipArchiveEntry entry) throws IOException {
                    // Repeated text and SpreadsheetML control escapes can legitimately exceed
                    // the reader's ZIP inflation ratio. Huffman coding keeps the normal safety
                    // checks effective without LZ77 expanding this one part by hundreds of times.
                    def.setStrategy("xl/sharedStrings.xml".equals(entry.getName())
                            ? Deflater.HUFFMAN_ONLY : Deflater.DEFAULT_STRATEGY);
                    super.putArchiveEntry(entry);
                }
            };
            archive.setUseZip64(Zip64Mode.AsNeeded);
            return archive;
        }
    }

    /** Protect literal SpreadsheetML escapes and XML-invalid UTF-16 before serializing shared strings. */
    private static String spreadsheetText(String value) {
        StringBuilder escaped = null;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean literalEscape = character == '_' && index + 6 < value.length()
                    && value.charAt(index + 1) == 'x' && value.charAt(index + 6) == '_'
                    && hex(value.charAt(index + 2)) && hex(value.charAt(index + 3))
                    && hex(value.charAt(index + 4)) && hex(value.charAt(index + 5));
            boolean unpaired = Character.isHighSurrogate(character)
                    ? index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))
                    : Character.isLowSurrogate(character)
                            && (index == 0 || !Character.isHighSurrogate(value.charAt(index - 1)));
            boolean invalidXml = character < 0x20 && character != '\t' && character != '\n' && character != '\r'
                    || character == 0xfffe || character == 0xffff || unpaired;
            if ((literalEscape || invalidXml) && escaped == null) {
                escaped = new StringBuilder(value.length() + 16).append(value, 0, index);
            }
            if (escaped != null) {
                if (literalEscape || invalidXml) {
                    escaped.append("_x");
                    for (int shift = 12; shift >= 0; shift -= 4) {
                        escaped.append("0123456789ABCDEF".charAt((character >>> shift) & 0xf));
                    }
                    escaped.append('_');
                } else {
                    escaped.append(character);
                }
            }
        }
        return escaped == null ? value : escaped.toString();
    }

    private static boolean hex(char character) {
        return character >= '0' && character <= '9' || character >= 'a' && character <= 'f'
                || character >= 'A' && character <= 'F';
    }

    private static final class ColumnWidths {
        private static final int MINIMUM = 2_800;
        private static final int MAXIMUM = 18_000;
        private static final int MAX_CHARACTERS = (MAXIMUM - 512 + 255) / 256;
        private final int[] characters;

        private ColumnWidths(String[] headers) {
            characters = new int[headers.length];
            for (int column = 0; column < headers.length; column++) include(column, headers[column]);
        }

        private void include(int column, Object value) {
            if (column >= characters.length || value == null || characters[column] >= MAX_CHARACTERS) return;
            String text = value.toString();
            int current = 0;
            int longest = 0;
            for (int offset = 0; offset < text.length();) {
                int codePoint = text.codePointAt(offset);
                if (codePoint == '\n' || codePoint == '\r') {
                    longest = Math.max(longest, current);
                    current = 0;
                } else {
                    current += codePoint == '\t' ? 4 : Character.isSupplementaryCodePoint(codePoint) ? 2 : 1;
                    if (current >= MAX_CHARACTERS) {
                        longest = MAX_CHARACTERS;
                        break;
                    }
                }
                offset += Character.charCount(codePoint);
            }
            characters[column] = Math.max(characters[column], Math.max(longest, current));
        }

        private void apply(Sheet sheet) {
            for (int column = 0; column < characters.length; column++) {
                sheet.setColumnWidth(column, Math.max(MINIMUM, Math.min(MAXIMUM, characters[column] * 256 + 512)));
            }
        }
    }

    private record WorkbookStyles(CellStyle header, CellStyle body) {
        private WorkbookStyles(Workbook workbook) {
            this(headerStyle(workbook), bodyStyle(workbook));
        }

        private static CellStyle headerStyle(Workbook workbook) {
            Font font = workbook.createFont();
            font.setBold(true);
            font.setColor(IndexedColors.WHITE.getIndex());
            CellStyle style = workbook.createCellStyle();
            style.setFont(font);
            style.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setAlignment(HorizontalAlignment.LEFT);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBottomBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
            return style;
        }

        private static CellStyle bodyStyle(Workbook workbook) {
            CellStyle style = workbook.createCellStyle();
            style.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.TOP);
            return style;
        }
    }

    private static final class TextStore {

        private final Map<String, String> values = new LinkedHashMap<>();

        private String reference(String value) {
            String key = "TXT-" + (values.size() + 1) + "-"
                    + sha256(value.getBytes(StandardCharsets.UTF_8)).substring(0, 12);
            values.put(key, value);
            return TEXT_REFERENCE_PREFIX + key;
        }

        private Map<String, String> values() {
            return values;
        }
    }
}
