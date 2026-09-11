package com.adelylria.ringlog.importexport.nativeformat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.adelylria.ringlog.database.Database;
import com.adelylria.ringlog.importexport.ImportSource;
import com.adelylria.ringlog.importexport.nativeformat.NativeExportModel.NativeRow;
import com.adelylria.ringlog.importexport.nativeformat.NativeValueCodec.TextStore;
import com.adelylria.ringlog.storage.AppPaths;
import com.adelylria.ringlog.storage.MediaPathResolver;

/** Creates a self-validating stable-key RingLog Export v1 snapshot. */
public final class RingLogExporter {

    private static final String WORKBOOK_NAME = "ringlog-export.xlsx";

    private final String databaseFile;
    private final MediaPathResolver mediaResolver;

    public RingLogExporter(String databaseFile) {
        this(
                databaseFile,
                new MediaPathResolver(AppPaths.forDatabase(Path.of(databaseFile)))
        );
    }

    public RingLogExporter(String databaseFile, MediaPathResolver mediaResolver) {
        if (databaseFile == null || databaseFile.isBlank()) {
            throw new IllegalArgumentException("Indica la base de datos que quieres exportar.");
        }
        this.databaseFile = Path.of(databaseFile).toAbsolutePath().normalize().toString();
        this.mediaResolver = java.util.Objects.requireNonNull(mediaResolver, "mediaResolver");
    }

    public ExportResult exportTo(Path destination) throws RingLogExportException {
        if (destination == null) {
            throw new RingLogExportException("Selecciona dónde guardar el export de RingLog.");
        }
        Path workbook = null;
        Path packaged = null;
        try {
            Database.validate(databaseFile);
            String exportId = UUID.randomUUID().toString();
            ExportDataset dataset = readSnapshot(exportId);
            boolean zip = !dataset.binaries().isEmpty();
            Path output = outputPath(destination.toAbsolutePath().normalize(), zip);
            Files.createDirectories(output.getParent());
            workbook = Files.createTempFile(output.getParent(), ".ringlog-export-", ".xlsx");
            writeWorkbook(workbook, dataset);

            Path candidate;
            if (zip) {
                packaged = Files.createTempFile(output.getParent(), ".ringlog-export-", ".zip");
                writeZip(packaged, workbook, dataset.binaries());
                candidate = packaged;
            } else {
                candidate = workbook;
            }
            validate(candidate, exportId, dataset.binaries().size());
            replace(candidate, output);
            if (candidate.equals(workbook)) {
                workbook = null;
            } else {
                packaged = null;
            }
            return new ExportResult(output, exportId, dataset.binaries().size());
        } catch (RingLogExportException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new RingLogExportException("No se pudo leer el estado de RingLog.", exception);
        } catch (IOException | RuntimeException exception) {
            throw new RingLogExportException(
                    "No se pudo crear un export nativo verificable.", exception
            );
        } finally {
            deleteQuietly(workbook);
            deleteQuietly(packaged);
        }
    }

    private ExportDataset readSnapshot(String exportId)
            throws SQLException, IOException, RingLogExportException {
        try (Connection connection = Database.getConnection(databaseFile)) {
            connection.setAutoCommit(false);
            try {
                Map<String, List<NativeRow>> sheets = new LinkedHashMap<>();
                sheets.put("species", rows(connection, "species", """
                        SELECT stable_key, code, scientific_name, common_name, active,
                               created_at, updated_at FROM species ORDER BY stable_key
                        """));
                sheets.put("birds", rows(connection, "birds", """
                        SELECT b.stable_key, b.ring_number,
                               s.stable_key AS species_stable_key,
                               b.created_at, b.updated_at
                        FROM bird b JOIN species s ON s.id = b.species_id
                        ORDER BY b.stable_key
                        """));
                sheets.put("places", rows(connection, "places", """
                        SELECT stable_key, name, locality, autonomous_community, country,
                               latitude, longitude, notes, is_favorite, is_default, active,
                               created_at, updated_at
                        FROM place ORDER BY stable_key
                        """));
                sheets.put("events", rows(connection, "events", """
                        SELECT e.stable_key, e.migration_key,
                               b.stable_key AS bird_stable_key, e.event_type, e.event_date,
                               e.event_time, p.stable_key AS place_stable_key, e.location_text,
                               e.sex_code, e.age_euring_code, e.fat_score, e.muscle_score,
                               e.ringer_initials, e.status, e.reproductive_status,
                               e.moult_intensity, e.moult_extension, e.bird_condition,
                               e.return_status, e.wing, e.p3, e.torso, e.weight,
                               e.observations, e.clouds, e.rain, e.thermal_sensation,
                               e.wind, e.capture_type, e.is_dead, e.source_name,
                               e.source_reference, e.review_status, e.review_note,
                               e.created_at, e.updated_at
                        FROM bird_event e
                        JOIN bird b ON b.id = e.bird_id
                        LEFT JOIN place p ON p.id = e.place_id
                        ORDER BY e.stable_key
                        """));
                BinaryRows photos = photoRows(connection);
                sheets.put("photos", photos.rows());
                sheets.put("import_batches", rows(connection, "import_batches", """
                        SELECT stable_key, source_format, format_version, source_name,
                               source_reference, fingerprint, export_id, import_mode, imported_at
                        FROM import_batch ORDER BY stable_key
                        """));
                sheets.put("import_metadata", rows(connection, "import_metadata", """
                        SELECT b.stable_key AS import_batch_stable_key,
                               m.metadata_key, m.metadata_value
                        FROM import_metadata m
                        JOIN import_batch b ON b.id = m.import_batch_id
                        ORDER BY b.stable_key, m.metadata_key
                        """));
                sheets.put("event_source_aliases", rows(connection, "event_source_aliases", """
                        SELECT a.stable_key, e.stable_key AS event_stable_key,
                               a.source_name, a.source_reference, a.created_at
                        FROM event_source_alias a
                        JOIN bird_event e ON e.id = a.event_id
                        ORDER BY a.stable_key
                        """));
                sheets.put("source_records", rows(connection, "source_records", """
                        SELECT r.stable_key, b.stable_key AS import_batch_stable_key,
                               r.source_name, r.source_section, r.source_reference,
                               r.ring_number, r.raw_payload, r.created_at
                        FROM legacy_source_record r
                        JOIN import_batch b ON b.id = r.import_batch_id
                        ORDER BY r.stable_key
                        """));
                BinaryRows unassigned = unassignedRows(connection);
                sheets.put("unassigned_photos", unassigned.rows());
                sheets.put("migration_audit", rows(connection, "migration_audit", """
                        SELECT a.stable_key, b.stable_key AS import_batch_stable_key,
                               a.source_name, a.source_section, a.source_reference,
                               a.source_field, a.original_value, a.original_display,
                               a.destination, a.normalized_value, a.status, a.note
                        FROM migration_audit a
                        JOIN import_batch b ON b.id = a.import_batch_id
                        ORDER BY a.stable_key
                        """));
                sheets.put("migration_conflicts", rows(connection, "migration_conflicts", """
                        SELECT c.stable_key, c.conflict_key,
                               b.stable_key AS import_batch_stable_key,
                               e.stable_key AS event_stable_key, c.ring_number,
                               c.conflict_type, c.event_type, c.field_name,
                               c.canonical_value, c.alternative_value,
                               c.canonical_source_reference, c.alternative_source_reference,
                               c.canonical_snapshot, c.alternative_snapshot, c.original_note,
                               c.status, c.resolution_type, c.resolution_value,
                               c.resolved_at, c.resolution_notes, c.created_at
                        FROM migration_conflict c
                        JOIN import_batch b ON b.id = c.import_batch_id
                        LEFT JOIN bird_event e ON e.id = c.event_id
                        ORDER BY c.stable_key
                        """));
                sheets.put("migration_warnings", rows(connection, "migration_warnings", """
                        SELECT w.stable_key, b.stable_key AS import_batch_stable_key,
                               w.severity, w.source, w.reference, w.message
                        FROM migration_warning w
                        JOIN import_batch b ON b.id = w.import_batch_id
                        ORDER BY w.stable_key
                        """));
                connection.rollback();

                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put("format", "RingLog Export");
                metadata.put("format_version", "1");
                metadata.put("schema_version", Integer.toString(Database.SCHEMA_VERSION));
                metadata.put("export_id", exportId);
                metadata.put("generated_at", Instant.now().toString());
                for (Map.Entry<String, List<NativeRow>> entry : sheets.entrySet()) {
                    metadata.put(entry.getKey() + "_count",
                            Integer.toString(entry.getValue().size()));
                }
                List<Binary> binaries = new ArrayList<>(photos.binaries());
                binaries.addAll(unassigned.binaries());
                binaries.sort(Comparator.comparing(Binary::packagePath));
                return new ExportDataset(
                        Map.copyOf(metadata), Map.copyOf(sheets), List.copyOf(binaries)
                );
            } catch (SQLException | IOException | RingLogExportException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        }
    }

    private static List<NativeRow> rows(Connection connection, String sheet, String sql)
            throws SQLException {
        List<String> columns = NativeSchema.SHEETS.get(sheet);
        List<NativeRow> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet values = statement.executeQuery(sql)) {
            while (values.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                for (String column : columns) {
                    row.put(column, values.getString(column));
                }
                result.add(new NativeRow(row));
            }
        }
        return List.copyOf(result);
    }

    private BinaryRows photoRows(Connection connection)
            throws SQLException, IOException, RingLogExportException {
        List<NativeRow> rows = new ArrayList<>();
        List<Binary> binaries = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT ep.*, e.stable_key AS event_stable_key
                     FROM event_photo ep
                     JOIN bird_event e ON e.id = ep.event_id
                     ORDER BY ep.stable_key
                     """)) {
            while (result.next()) {
                String stableKey = result.getString("stable_key");
                Path source = requiredBinary(
                        result.getString("file_path"), stableKey, false
                );
                Binary binary = binary(
                        "photos/events/" + stableKey + extension(result.getString("file_name")),
                        source
                );
                binaries.add(binary);
                Map<String, String> row = new LinkedHashMap<>();
                row.put("stable_key", stableKey);
                row.put("event_stable_key", result.getString("event_stable_key"));
                row.put("file_name", result.getString("file_name"));
                row.put("original_file_path", result.getString("file_path"));
                row.put("media_path", binary.packagePath());
                row.put("mime_type", result.getString("mime_type"));
                row.put("source_name", result.getString("source_name"));
                row.put("source_reference", result.getString("source_reference"));
                row.put("content_sha256", result.getString("content_sha256"));
                row.put("content_size", result.getString("content_size"));
                row.put("package_sha256", binary.sha256());
                row.put("package_size", Long.toString(binary.size()));
                row.put("created_at", result.getString("created_at"));
                rows.add(new NativeRow(row));
            }
        }
        return new BinaryRows(List.copyOf(rows), List.copyOf(binaries));
    }

    private BinaryRows unassignedRows(Connection connection)
            throws SQLException, IOException, RingLogExportException {
        List<NativeRow> rows = new ArrayList<>();
        List<Binary> binaries = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT u.*, b.stable_key AS import_batch_stable_key
                     FROM legacy_unassigned_photo u
                     JOIN import_batch b ON b.id = u.import_batch_id
                     ORDER BY u.stable_key
                     """)) {
            while (result.next()) {
                String stableKey = result.getString("stable_key");
                String filePath = result.getString("file_path");
                Binary binary = null;
                if (filePath != null) {
                    Path source = requiredBinary(filePath, stableKey, true);
                    binary = binary(
                            "photos/unassigned/" + stableKey
                                    + extension(result.getString("file_name")), source
                    );
                    binaries.add(binary);
                }
                Map<String, String> row = new LinkedHashMap<>();
                row.put("stable_key", stableKey);
                row.put("import_batch_stable_key", result.getString("import_batch_stable_key"));
                row.put("source_name", result.getString("source_name"));
                row.put("source_reference", result.getString("source_reference"));
                row.put("file_name", result.getString("file_name"));
                row.put("original_file_path", filePath);
                row.put("media_path", binary == null ? null : binary.packagePath());
                row.put("mime_type", result.getString("mime_type"));
                row.put("content_sha256", result.getString("content_sha256"));
                row.put("content_size", result.getString("content_size"));
                row.put("package_sha256", binary == null ? null : binary.sha256());
                row.put("package_size", binary == null ? null : Long.toString(binary.size()));
                row.put("width", result.getString("width"));
                row.put("height", result.getString("height"));
                row.put("created_at", result.getString("created_at"));
                rows.add(new NativeRow(row));
            }
        }
        return new BinaryRows(List.copyOf(rows), List.copyOf(binaries));
    }

    private Path requiredBinary(String value, String stableKey, boolean unassigned)
            throws RingLogExportException {
        try {
            Path file = unassigned
                    ? mediaResolver.resolveUnassignedPhoto(value)
                    : mediaResolver.resolveEventPhoto(value);
            if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
                throw new RingLogExportException(
                        "No se encuentra el binario necesario " + stableKey + "."
                );
            }
            return file;
        } catch (RuntimeException exception) {
            throw new RingLogExportException(
                    "La ruta del binario " + stableKey + " no es válida.", exception
            );
        }
    }

    private static Binary binary(String packagePath, Path source) throws IOException {
        return new Binary(packagePath, source, sha256(source), Files.size(source));
    }

    private static void writeWorkbook(Path destination, ExportDataset dataset)
            throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            CellStyle header = headerStyle(workbook);
            Sheet metadata = workbook.createSheet("metadata");
            Row metadataHeader = metadata.createRow(0);
            metadataHeader.createCell(0).setCellValue("key");
            metadataHeader.createCell(1).setCellValue("value");
            metadataHeader.getCell(0).setCellStyle(header);
            metadataHeader.getCell(1).setCellStyle(header);
            int metadataRow = 1;
            for (Map.Entry<String, String> entry : dataset.metadata().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                Row row = metadata.createRow(metadataRow++);
                row.createCell(0).setCellValue(entry.getKey());
                row.createCell(1).setCellValue(entry.getValue());
            }
            metadata.setColumnWidth(0, 7_000);
            metadata.setColumnWidth(1, 18_000);

            TextStore textStore = new TextStore();
            for (Map.Entry<String, List<String>> definition : NativeSchema.SHEETS.entrySet()) {
                Sheet sheet = workbook.createSheet(definition.getKey());
                Row columns = sheet.createRow(0);
                for (int index = 0; index < definition.getValue().size(); index++) {
                    columns.createCell(index).setCellValue(definition.getValue().get(index));
                    columns.getCell(index).setCellStyle(header);
                }
                int rowIndex = 1;
                for (NativeRow nativeRow : dataset.sheets().get(definition.getKey())) {
                    Row row = sheet.createRow(rowIndex++);
                    for (int column = 0; column < definition.getValue().size(); column++) {
                        String name = definition.getValue().get(column);
                        row.createCell(column).setCellValue(
                                NativeValueCodec.encode(nativeRow.value(name), textStore)
                        );
                    }
                }
                sheet.createFreezePane(0, 1);
                for (int index = 0; index < Math.min(definition.getValue().size(), 12); index++) {
                    sheet.setColumnWidth(index, 6_000);
                }
            }

            Sheet text = workbook.createSheet("text_content");
            Row textHeader = text.createRow(0);
            for (int index = 0; index < 4; index++) {
                textHeader.createCell(index).setCellValue(
                        List.of("text_key", "chunk_index", "chunk_count", "data_base64").get(index)
                );
            }
            int textRow = 1;
            for (Map.Entry<String, List<String>> entry : textStore.chunks().entrySet()) {
                for (int index = 0; index < entry.getValue().size(); index++) {
                    Row row = text.createRow(textRow++);
                    row.createCell(0).setCellValue(entry.getKey());
                    row.createCell(1).setCellValue(index);
                    row.createCell(2).setCellValue(entry.getValue().size());
                    row.createCell(3).setCellValue(entry.getValue().get(index));
                }
            }
            workbook.setSheetVisibility(
                    workbook.getSheetIndex(text), org.apache.poi.ss.usermodel.SheetVisibility.VERY_HIDDEN
            );
            try (OutputStream output = Files.newOutputStream(destination)) {
                workbook.write(output);
            }
        }
    }

    private static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        return style;
    }

    private static void writeZip(Path zipFile, Path workbook, List<Binary> binaries)
            throws IOException {
        try (OutputStream file = Files.newOutputStream(zipFile);
             ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry(WORKBOOK_NAME));
            Files.copy(workbook, zip);
            zip.closeEntry();
            for (Binary binary : binaries) {
                zip.putNextEntry(new ZipEntry(binary.packagePath()));
                MessageDigest digest = sha256Digest();
                long total = 0;
                try (InputStream input = Files.newInputStream(binary.source())) {
                    byte[] buffer = new byte[16_384];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        digest.update(buffer, 0, read);
                        zip.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
                if (total != binary.size() || !HexFormat.of().formatHex(digest.digest())
                        .equals(binary.sha256())) {
                    throw new IOException(
                            "Un binario cambió mientras se escribía el export: "
                                    + binary.packagePath()
                    );
                }
            }
        }
    }

    private static void validate(Path candidate, String exportId, int binaries)
            throws RingLogExportException {
        try (ImportSource source = ImportSource.open(candidate)) {
            NativeExportModel model = new RingLogExportReader().read(source);
            if (!exportId.equals(model.exportId()) || model.media().size() != binaries) {
                throw new RingLogExportException(
                        "La autoverificación del export no coincide con su contenido."
                );
            }
        } catch (RingLogExportException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RingLogExportException(
                    "El export generado no superó su autoverificación.", exception
            );
        }
    }

    private static Path outputPath(Path requested, boolean zip) {
        String name = requested.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx") || lower.endsWith(".zip")) {
            name = name.substring(0, name.lastIndexOf('.'));
        }
        return requested.resolveSibling(name + (zip ? ".zip" : ".xlsx"));
    }

    private static String extension(String name) {
        if (name == null) {
            return "";
        }
        String safe = Path.of(name).getFileName().toString();
        int dot = safe.lastIndexOf('.');
        if (dot <= 0 || dot == safe.length() - 1) {
            return "";
        }
        String extension = safe.substring(dot).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,10}") ? extension : "";
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 no está disponible.", impossible);
        }
    }

    private static void replace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Preserve the useful export error.
        }
    }

    private record Binary(String packagePath, Path source, String sha256, long size) {
    }

    private record BinaryRows(List<NativeRow> rows, List<Binary> binaries) {
    }

    private record ExportDataset(
            Map<String, String> metadata,
            Map<String, List<NativeRow>> sheets,
            List<Binary> binaries
    ) {
    }
}
