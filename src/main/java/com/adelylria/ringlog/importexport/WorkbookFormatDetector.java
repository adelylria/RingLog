package com.adelylria.ringlog.importexport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/** Detects a supported profile from exact metadata and required sheets only. */
public final class WorkbookFormatDetector {

    public WorkbookProfile detect(Path source) throws ImportValidationException {
        try (ImportSource opened = ImportSource.open(source)) {
            return detect(opened);
        } catch (ImportValidationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ImportValidationException(
                    "No se pudieron limpiar los archivos temporales de importación.", exception
            );
        }
    }

    public WorkbookProfile detect(ImportSource source) throws ImportValidationException {
        if (source == null) {
            throw new ImportValidationException("No hay una fuente de importación abierta.");
        }
        try (InputStream input = java.nio.file.Files.newInputStream(source.workbookPath());
             Workbook workbook = WorkbookFactory.create(input)) {
            Map<String, String> metadata = readMetadata(workbook);
            ImportFormat format = selectFormat(metadata);
            Set<String> sheets = sheetNames(workbook);
            Set<String> missing = new java.util.TreeSet<>(format.requiredSheets());
            missing.removeAll(sheets);
            if (!missing.isEmpty()) {
                throw new ImportValidationException(
                        "Faltan hojas obligatorias para " + format + ": "
                                + String.join(", ", missing)
                );
            }
            if (source.packaged() && format != ImportFormat.RINGLOG_EXPORT_V1) {
                throw new ImportValidationException(
                        "El formato ZIP solo está permitido para RingLog Export v1."
                );
            }
            return new WorkbookProfile(format, metadata, sheets);
        } catch (ImportValidationException exception) {
            throw exception;
        } catch (EncryptedDocumentException exception) {
            throw new ImportValidationException(
                    "El workbook está cifrado y RingLog no puede analizarlo.", exception
            );
        } catch (IOException | RuntimeException exception) {
            throw new ImportValidationException(
                    "El workbook está dañado o no es un XLSX compatible.", exception
            );
        }
    }

    private static Map<String, String> readMetadata(Workbook workbook)
            throws ImportValidationException {
        Sheet sheet = exactSheet(workbook, "metadata");
        if (sheet == null) {
            throw new ImportValidationException("Falta la hoja obligatoria metadata.");
        }
        Row header = sheet.getRow(0);
        if (header == null
                || !"key".equals(readString(header.getCell(0), "cabecera metadata"))
                || !"value".equals(readString(header.getCell(1), "cabecera metadata"))) {
            throw new ImportValidationException(
                    "La hoja metadata debe empezar con las columnas key y value."
            );
        }
        rejectUnexpectedCells(header, 2);

        Map<String, String> metadata = new LinkedHashMap<>();
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row == null || isEmpty(row)) {
                continue;
            }
            rejectUnexpectedCells(row, 2);
            String key = readString(row.getCell(0), "clave metadata");
            String value = readString(row.getCell(1), "valor metadata");
            if (key.isBlank() || !key.equals(key.trim())) {
                throw new ImportValidationException("Hay una clave metadata vacía o mal formada.");
            }
            if (metadata.putIfAbsent(key, value) != null) {
                throw new ImportValidationException("La clave metadata está duplicada: " + key);
            }
        }
        if (!metadata.containsKey("format") || !metadata.containsKey("format_version")) {
            throw new ImportValidationException(
                    "metadata debe contener format y format_version."
            );
        }
        return Map.copyOf(metadata);
    }

    private static ImportFormat selectFormat(Map<String, String> metadata)
            throws ImportValidationException {
        String format = metadata.get("format");
        String version = metadata.get("format_version");
        if ("RingLog Import".equals(format)) {
            if ("5".equals(version)) {
                if (!"5.2".equals(metadata.get("migrator_version"))) {
                    throw new ImportValidationException(
                            "Legacy v5 requiere migrator_version 5.2."
                    );
                }
                return ImportFormat.LEGACY_V5;
            }
            if ("3".equals(version)) {
                if (!"RingLog full backup".equals(metadata.get("backup_kind"))) {
                    throw new ImportValidationException(
                            "El backup v3 requiere backup_kind = RingLog full backup."
                    );
                }
                return ImportFormat.RINGLOG_BACKUP_V3;
            }
            throw new ImportValidationException(
                    "La versión " + version + " de RingLog Import no es compatible."
            );
        }
        if ("RingLog Export".equals(format)) {
            if (!"1".equals(version)) {
                throw new ImportValidationException(
                        "La versión " + version + " de RingLog Export no es compatible."
                );
            }
            return ImportFormat.RINGLOG_EXPORT_V1;
        }
        throw new ImportValidationException("El formato del workbook es desconocido.");
    }

    private static Set<String> sheetNames(Workbook workbook) {
        Set<String> names = new LinkedHashSet<>();
        for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
            names.add(workbook.getSheetName(index));
        }
        return Set.copyOf(names);
    }

    private static Sheet exactSheet(Workbook workbook, String expected) {
        for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
            if (expected.equals(workbook.getSheetName(index))) {
                return workbook.getSheetAt(index);
            }
        }
        return null;
    }

    private static String readString(Cell cell, String description)
            throws ImportValidationException {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return "";
        }
        if (cell.getCellType() == CellType.FORMULA) {
            throw new ImportValidationException(
                    "No se permiten fórmulas en " + description + "."
            );
        }
        if (cell.getCellType() != CellType.STRING) {
            throw new ImportValidationException(
                    "Se esperaba texto en " + description + "."
            );
        }
        return cell.getStringCellValue();
    }

    private static void rejectUnexpectedCells(Row row, int allowed)
            throws ImportValidationException {
        for (int column = allowed; column < row.getLastCellNum(); column++) {
            Cell cell = row.getCell(column);
            if (cell != null && cell.getCellType() == CellType.FORMULA) {
                throw new ImportValidationException("No se permiten fórmulas en metadata.");
            }
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                throw new ImportValidationException(
                        "La hoja metadata contiene columnas inesperadas."
                );
            }
        }
    }

    private static boolean isEmpty(Row row) {
        for (int column = 0; column < row.getLastCellNum(); column++) {
            Cell cell = row.getCell(column);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                return false;
            }
        }
        return true;
    }
}
