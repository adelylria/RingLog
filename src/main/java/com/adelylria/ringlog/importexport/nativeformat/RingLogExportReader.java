package com.adelylria.ringlog.importexport.nativeformat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import com.adelylria.ringlog.importexport.ImportFormat;
import com.adelylria.ringlog.importexport.ImportSource;
import com.adelylria.ringlog.importexport.ImportValidationException;
import com.adelylria.ringlog.importexport.WorkbookFormatDetector;
import com.adelylria.ringlog.importexport.WorkbookProfile;
import com.adelylria.ringlog.importexport.nativeformat.NativeExportModel.MediaFile;
import com.adelylria.ringlog.importexport.nativeformat.NativeExportModel.NativeRow;

/** Strict reader for the lossless, stable-key RingLog Export v1 contract. */
public final class RingLogExportReader {

    private static final int MAX_TEXT_ROWS = 200_000;
    private static final int MAX_DATA_ROWS = 1_000_000;
    private static final long MAX_MEDIA_BYTES = 2_000_000_000L;

    public NativeExportModel read(ImportSource source) throws ImportValidationException {
        WorkbookProfile profile = new WorkbookFormatDetector().detect(source);
        if (profile.format() != ImportFormat.RINGLOG_EXPORT_V1) {
            throw new ImportValidationException(
                    "El lector nativo recibió un formato diferente."
            );
        }
        try (InputStream input = Files.newInputStream(source.workbookPath());
             Workbook workbook = WorkbookFactory.create(input)) {
            rejectFormulas(workbook);
            Map<String, String> textStore = readTextStore(workbook);
            Map<String, List<NativeRow>> sheets = new LinkedHashMap<>();
            int totalRows = 0;
            for (Map.Entry<String, List<String>> definition : NativeSchema.SHEETS.entrySet()) {
                List<NativeRow> rows = readRows(
                        workbook, definition.getKey(), definition.getValue(), textStore
                );
                totalRows += rows.size();
                if (totalRows > MAX_DATA_ROWS) {
                    throw new ImportValidationException(
                            "El export nativo contiene demasiadas filas."
                    );
                }
                sheets.put(definition.getKey(), rows);
            }
            NativeExportModel model = new NativeExportModel(
                    profile.metadata(), sheets, resolveMedia(source, sheets)
            );
            validate(model, source.packaged());
            return model;
        } catch (ImportValidationException exception) {
            throw exception;
        } catch (EncryptedDocumentException exception) {
            throw new ImportValidationException("El export nativo está cifrado.", exception);
        } catch (IOException | RuntimeException exception) {
            throw new ImportValidationException(
                    exception.getMessage() == null
                            ? "No se pudo leer el export nativo."
                            : "Export nativo no válido: " + exception.getMessage(),
                    exception
            );
        }
    }

    private static List<NativeRow> readRows(
            Workbook workbook,
            String sheetName,
            List<String> columns,
            Map<String, String> textStore
    ) {
        Sheet sheet = requiredSheet(workbook, sheetName);
        exactHeader(sheet, columns);
        List<NativeRow> result = new ArrayList<>();
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row == null || empty(row)) {
                continue;
            }
            rejectExtraCells(row, columns.size());
            Map<String, String> values = new LinkedHashMap<>();
            for (int column = 0; column < columns.size(); column++) {
                Cell cell = row.getCell(column);
                if (cell == null || cell.getCellType() != CellType.STRING) {
                    throw invalid(sheetName, index, "Todas las celdas deben estar codificadas.");
                }
                values.put(columns.get(column), NativeValueCodec.decode(
                        cell.getStringCellValue(), textStore
                ));
            }
            result.add(new NativeRow(values));
        }
        return List.copyOf(result);
    }

    private static Map<String, String> readTextStore(Workbook workbook) {
        Sheet sheet = requiredSheet(workbook, "text_content");
        exactHeader(sheet, List.of("text_key", "chunk_index", "chunk_count", "data_base64"));
        if (sheet.getPhysicalNumberOfRows() - 1 > MAX_TEXT_ROWS) {
            throw new IllegalArgumentException("Hay demasiados fragmentos de texto interno.");
        }
        Map<String, ChunkSet> chunks = new LinkedHashMap<>();
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row == null || empty(row)) {
                continue;
            }
            rejectExtraCells(row, 4);
            String key = stringCell(row, 0, "text_key");
            int chunkIndex = integerCell(row, 1, "chunk_index");
            int chunkCount = integerCell(row, 2, "chunk_count");
            String data = stringCell(row, 3, "data_base64");
            if (key.isBlank() || chunkCount <= 0 || chunkCount > MAX_TEXT_ROWS
                    || chunkIndex < 0 || chunkIndex >= chunkCount) {
                throw invalid("text_content", index, "Fragmento interno no válido.");
            }
            ChunkSet set = chunks.computeIfAbsent(key, ignored -> new ChunkSet(chunkCount));
            if (set.values.length != chunkCount || set.values[chunkIndex] != null) {
                throw invalid("text_content", index, "Fragmento interno duplicado.");
            }
            set.values[chunkIndex] = data;
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, ChunkSet> entry : chunks.entrySet()) {
            StringBuilder value = new StringBuilder();
            for (String chunk : entry.getValue().values) {
                if (chunk == null) {
                    throw new IllegalArgumentException("Falta un fragmento de texto interno.");
                }
                value.append(chunk);
            }
            result.put(entry.getKey(), value.toString());
        }
        return Map.copyOf(result);
    }

    private static Map<String, MediaFile> resolveMedia(
            ImportSource source,
            Map<String, List<NativeRow>> sheets
    ) throws ImportValidationException, IOException {
        Map<String, MediaFile> result = new LinkedHashMap<>();
        long total = 0;
        for (String sheet : List.of("photos", "unassigned_photos")) {
            for (NativeRow row : sheets.get(sheet)) {
                String packagePath = row.value("media_path");
                if (packagePath == null) {
                    if ("photos".equals(sheet) || row.value("original_file_path") != null) {
                        throw new ImportValidationException(
                                "Falta un binario necesario para restaurar "
                                        + row.value("stable_key") + "."
                        );
                    }
                    continue;
                }
                if (!source.packaged()) {
                    throw new ImportValidationException(
                            "Un export con binarios externos debe estar empaquetado como ZIP."
                    );
                }
                Path file = source.resolveMedia(packagePath);
                long size = Files.size(file);
                total += size;
                if (total > MAX_MEDIA_BYTES) {
                    throw new ImportValidationException(
                            "Los binarios del export superan el límite seguro."
                    );
                }
                String declaredHash = required(row, "package_sha256");
                long declaredSize = parseLong(required(row, "package_size"), "package_size");
                String actual = sha256(file);
                if (size != declaredSize || !actual.equalsIgnoreCase(declaredHash)) {
                    throw new ImportValidationException(
                            "El binario " + packagePath + " no coincide con el workbook."
                    );
                }
                if (result.putIfAbsent(packagePath,
                        new MediaFile(packagePath, file, actual, size)) != null) {
                    throw new ImportValidationException(
                            "La ruta de binario está repetida: " + packagePath
                    );
                }
            }
        }
        return Map.copyOf(result);
    }

    private static void validate(NativeExportModel model, boolean packaged) {
        UUID.fromString(requiredMetadata(model, "export_id"));
        if (!Set.of("3", "4").contains(requiredMetadata(model, "schema_version"))) {
            throw new IllegalArgumentException(
                    "El export no corresponde a un schema RingLog compatible."
            );
        }
        Set<String> species = unique(model.rows("species"), "stable_key", "species");
        Set<String> birds = unique(model.rows("birds"), "stable_key", "birds");
        Set<String> places = unique(model.rows("places"), "stable_key", "places");
        Set<String> events = unique(model.rows("events"), "stable_key", "events");
        unique(model.rows("photos"), "stable_key", "photos");
        Set<String> batches = unique(model.rows("import_batches"), "stable_key", "batches");
        unique(model.rows("event_source_aliases"), "stable_key", "aliases");
        unique(model.rows("source_records"), "stable_key", "source records");
        unique(model.rows("unassigned_photos"), "stable_key", "unassigned photos");
        unique(model.rows("migration_audit"), "stable_key", "audit");
        unique(model.rows("migration_conflicts"), "stable_key", "conflicts");
        unique(model.rows("migration_warnings"), "stable_key", "warnings");

        references(model.rows("birds"), "species_stable_key", species, false);
        references(model.rows("events"), "bird_stable_key", birds, false);
        references(model.rows("events"), "place_stable_key", places, true);
        references(model.rows("photos"), "event_stable_key", events, false);
        references(model.rows("event_source_aliases"), "event_stable_key", events, false);
        references(model.rows("import_metadata"), "import_batch_stable_key", batches, false);
        references(model.rows("source_records"), "import_batch_stable_key", batches, false);
        references(model.rows("unassigned_photos"), "import_batch_stable_key", batches, false);
        references(model.rows("migration_audit"), "import_batch_stable_key", batches, false);
        references(model.rows("migration_conflicts"), "import_batch_stable_key", batches, false);
        references(model.rows("migration_conflicts"), "event_stable_key", events, true);
        references(model.rows("migration_warnings"), "import_batch_stable_key", batches, false);
        if (!packaged && !model.media().isEmpty()) {
            throw new IllegalArgumentException("Los binarios nativos requieren un ZIP.");
        }
        for (Map.Entry<String, List<String>> definition : NativeSchema.SHEETS.entrySet()) {
            String expected = model.metadata().get(definition.getKey() + "_count");
            if (expected != null && parseLong(expected, definition.getKey() + "_count")
                    != model.rows(definition.getKey()).size()) {
                throw new IllegalArgumentException(
                        "El contador no coincide para " + definition.getKey() + "."
                );
            }
        }
    }

    private static Set<String> unique(List<NativeRow> rows, String column, String label) {
        Set<String> result = new HashSet<>();
        for (NativeRow row : rows) {
            String value = required(row, column);
            UUID.fromString(value);
            if (!result.add(value)) {
                throw new IllegalArgumentException("Clave estable repetida en " + label + ".");
            }
        }
        return Set.copyOf(result);
    }

    private static void references(
            List<NativeRow> rows,
            String column,
            Set<String> targets,
            boolean nullable
    ) {
        for (NativeRow row : rows) {
            String value = row.value(column);
            if (value == null && nullable) {
                continue;
            }
            if (value == null || !targets.contains(value)) {
                throw new IllegalArgumentException("Referencia nativa no válida en " + column + ".");
            }
        }
    }

    private static String required(NativeRow row, String column) {
        String value = row.value(column);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Falta el valor obligatorio " + column + ".");
        }
        return value;
    }

    private static String requiredMetadata(NativeExportModel model, String key) {
        String value = model.metadata().get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Falta metadata nativa: " + key);
        }
        return value;
    }

    private static long parseLong(String value, String field) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Valor entero no válido en " + field + ".", exception);
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 no está disponible.", impossible);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void rejectFormulas(Workbook workbook) {
        for (Sheet sheet : workbook) {
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell.getCellType() == CellType.FORMULA) {
                        throw new IllegalArgumentException("El export nativo contiene fórmulas.");
                    }
                }
            }
        }
    }

    private static Sheet requiredSheet(Workbook workbook, String name) {
        Sheet sheet = workbook.getSheet(name);
        if (sheet == null) {
            throw new IllegalArgumentException("Falta la hoja nativa " + name + ".");
        }
        return sheet;
    }

    private static void exactHeader(Sheet sheet, List<String> columns) {
        Row header = sheet.getRow(0);
        if (header == null) {
            throw new IllegalArgumentException("Falta la cabecera de " + sheet.getSheetName());
        }
        rejectExtraCells(header, columns.size());
        for (int index = 0; index < columns.size(); index++) {
            if (!columns.get(index).equals(stringCell(header, index, columns.get(index)))) {
                throw new IllegalArgumentException(
                        "Cabecera no válida en " + sheet.getSheetName() + "."
                );
            }
        }
    }

    private static String stringCell(Row row, int index, String field) {
        Cell cell = row.getCell(index);
        if (cell == null || cell.getCellType() != CellType.STRING) {
            throw new IllegalArgumentException("La celda " + field + " debe ser texto.");
        }
        return cell.getStringCellValue();
    }

    private static int integerCell(Row row, int index, String field) {
        Cell cell = row.getCell(index);
        if (cell == null || cell.getCellType() != CellType.NUMERIC
                || cell.getNumericCellValue() != Math.rint(cell.getNumericCellValue())) {
            throw new IllegalArgumentException("La celda " + field + " debe ser entera.");
        }
        return Math.toIntExact((long) cell.getNumericCellValue());
    }

    private static void rejectExtraCells(Row row, int expected) {
        for (int index = expected; index < row.getLastCellNum(); index++) {
            Cell cell = row.getCell(index);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                throw new IllegalArgumentException("Hay columnas inesperadas en el export.");
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

    private static IllegalArgumentException invalid(String sheet, int row, String message) {
        return new IllegalArgumentException(sheet + " fila " + (row + 1) + ": " + message);
    }

    private static final class ChunkSet {
        private final String[] values;

        private ChunkSet(int count) {
            values = new String[count];
        }
    }
}
