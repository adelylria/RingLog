package com.adelylria.ringlog.importexport;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Contract tests for metadata-only profile detection and safe source opening. */
public final class WorkbookFormatDetectorTest {

    private WorkbookFormatDetectorTest() {
    }

    public static void detectsOnlyTheThreeApprovedProfiles() throws Exception {
        Path directory = Files.createTempDirectory("ringlog-format-profiles-");
        try {
            WorkbookFormatDetector detector = new WorkbookFormatDetector();

            Path legacy = writeProfile(
                    directory.resolve("cualquier-nombre.xlsx"),
                    metadata("RingLog Import", "5", "migrator_version", "5.2"),
                    ImportFormat.LEGACY_V5.requiredSheets(), false, false
            );
            require(detector.detect(legacy).format() == ImportFormat.LEGACY_V5,
                    "The exact v5.2 migrator metadata must select LEGACY_V5");

            Path backup = writeProfile(
                    directory.resolve("no-es-un-backup-por-nombre.xlsx"),
                    metadata("RingLog Import", "3", "backup_kind", "RingLog full backup"),
                    ImportFormat.RINGLOG_BACKUP_V3.requiredSheets(), false, false
            );
            require(detector.detect(backup).format() == ImportFormat.RINGLOG_BACKUP_V3,
                    "The exact current backup metadata must select RINGLOG_BACKUP_V3");

            Path nativeExport = writeProfile(
                    directory.resolve("parece-legacy.xlsx"),
                    metadata("RingLog Export", "1", "export_id",
                            "6e97265f-97bc-43e5-a365-4161ff50ef01"),
                    ImportFormat.RINGLOG_EXPORT_V1.requiredSheets(), false, false
            );
            WorkbookProfile profile = detector.detect(nativeExport);
            require(profile.format() == ImportFormat.RINGLOG_EXPORT_V1,
                    "Native v1 metadata must select RINGLOG_EXPORT_V1 regardless of filename");
            require("1".equals(profile.metadata().get("format_version")),
                    "Validated metadata must remain available to later readers");
            requireUnmodifiable(profile.metadata());
        } finally {
            deleteDirectory(directory);
        }
    }

    public static void rejectsUnknownIncompleteAndHybridMetadata() throws Exception {
        Path directory = Files.createTempDirectory("ringlog-format-invalid-");
        try {
            WorkbookFormatDetector detector = new WorkbookFormatDetector();

            Path withoutMetadata = directory.resolve("without-metadata.xlsx");
            try (Workbook workbook = new XSSFWorkbook();
                 OutputStream output = Files.newOutputStream(withoutMetadata)) {
                workbook.createSheet("species");
                workbook.write(output);
            }
            requireValidationFailure(() -> detector.detect(withoutMetadata), "metadata");

            Path unknown = writeProfile(directory.resolve("unknown.xlsx"),
                    metadata("Otro formato", "1", null, null), Set.of("metadata"), false, false);
            requireValidationFailure(() -> detector.detect(unknown), "desconocido");

            Path futureNative = writeProfile(directory.resolve("native-v2.xlsx"),
                    metadata("RingLog Export", "2", null, null),
                    ImportFormat.RINGLOG_EXPORT_V1.requiredSheets(), false, false);
            requireValidationFailure(() -> detector.detect(futureNative), "no es compatible");

            Path wrongMigrator = writeProfile(directory.resolve("legacy-51.xlsx"),
                    metadata("RingLog Import", "5", "migrator_version", "5.1"),
                    ImportFormat.LEGACY_V5.requiredSheets(), false, false);
            requireValidationFailure(() -> detector.detect(wrongMigrator), "5.2");

            Path backupWithoutKind = writeProfile(directory.resolve("backup-without-kind.xlsx"),
                    metadata("RingLog Import", "3", null, null),
                    ImportFormat.RINGLOG_BACKUP_V3.requiredSheets(), false, false);
            requireValidationFailure(() -> detector.detect(backupWithoutKind), "backup_kind");

            Set<String> missingEvents = new LinkedHashSet<>(ImportFormat.LEGACY_V5.requiredSheets());
            missingEvents.remove("events");
            Path missingSheet = writeProfile(directory.resolve("missing-sheet.xlsx"),
                    metadata("RingLog Import", "5", "migrator_version", "5.2"),
                    missingEvents, false, false);
            requireValidationFailure(() -> detector.detect(missingSheet), "events");

            Path duplicateMetadata = writeProfile(directory.resolve("duplicate.xlsx"),
                    metadata("RingLog Export", "1", null, null),
                    ImportFormat.RINGLOG_EXPORT_V1.requiredSheets(), true, false);
            requireValidationFailure(() -> detector.detect(duplicateMetadata), "duplicada");

            Path formulaMetadata = writeProfile(directory.resolve("formula.xlsx"),
                    metadata("RingLog Export", "1", null, null),
                    ImportFormat.RINGLOG_EXPORT_V1.requiredSheets(), false, true);
            requireValidationFailure(() -> detector.detect(formulaMetadata), "fórmula");
        } finally {
            deleteDirectory(directory);
        }
    }

    public static void opensNativeZipResolvesMediaAndCleansTemporaryFiles() throws Exception {
        Path directory = Files.createTempDirectory("ringlog-format-zip-");
        try {
            Path workbook = writeProfile(directory.resolve("source.xlsx"),
                    metadata("RingLog Export", "1", null, null),
                    ImportFormat.RINGLOG_EXPORT_V1.requiredSheets(), false, false);
            byte[] workbookBytes = Files.readAllBytes(workbook);
            Path archive = directory.resolve("ringlog-export.zip");
            writeZip(archive, List.of(
                    entry("ringlog-export.xlsx", workbookBytes),
                    entry("photos/events/example.jpg", new byte[]{1, 2, 3, 4})
            ));

            Path extractedRoot;
            try (ImportSource source = ImportSource.open(archive)) {
                require(source.packaged(), "A ZIP source must be marked as packaged");
                require("ringlog-export.xlsx".equals(source.workbookPath().getFileName().toString()),
                        "The native workbook must be at the ZIP root");
                require(Files.readAllBytes(source.resolveMedia("photos/events/example.jpg")).length == 4,
                        "Packaged media must resolve below the private extraction root");
                require(new WorkbookFormatDetector().detect(source).format()
                                == ImportFormat.RINGLOG_EXPORT_V1,
                        "A valid native ZIP must detect as RINGLOG_EXPORT_V1");
                extractedRoot = source.mediaRoot();
                require(Files.exists(extractedRoot), "Extraction root must exist while source is open");
            }
            require(!Files.exists(extractedRoot), "Closing a ZIP source must remove temporary files");

            try (ImportSource direct = ImportSource.open(workbook)) {
                require(!direct.packaged(), "A direct workbook must not be marked as packaged");
            }
            require(Files.isRegularFile(workbook),
                    "Closing a direct workbook source must never delete the user's file");
        } finally {
            deleteDirectory(directory);
        }
    }

    public static void rejectsUnsafeOrUnboundedArchives() throws Exception {
        Path directory = Files.createTempDirectory("ringlog-format-unsafe-zip-");
        try {
            Path workbook = writeProfile(directory.resolve("native.xlsx"),
                    metadata("RingLog Export", "1", null, null),
                    ImportFormat.RINGLOG_EXPORT_V1.requiredSheets(), false, false);
            byte[] workbookBytes = Files.readAllBytes(workbook);

            Path traversal = directory.resolve("traversal.zip");
            writeZip(traversal, List.of(
                    entry("ringlog-export.xlsx", workbookBytes),
                    entry("../escape.txt", new byte[]{1})
            ));
            requireValidationFailure(() -> ImportSource.open(traversal), "ruta");

            Path collision = directory.resolve("collision.zip");
            writeZip(collision, List.of(
                    entry("ringlog-export.xlsx", workbookBytes),
                    entry("photos/A.jpg", new byte[]{1}),
                    entry("photos/a.jpg", new byte[]{2})
            ));
            requireValidationFailure(() -> ImportSource.open(collision), "duplicada");

            Path secondWorkbook = directory.resolve("two-workbooks.zip");
            writeZip(secondWorkbook, List.of(
                    entry("ringlog-export.xlsx", workbookBytes),
                    entry("photos/other.xlsx", workbookBytes)
            ));
            requireValidationFailure(() -> ImportSource.open(secondWorkbook), "workbook");

            Path unexpectedRoot = directory.resolve("unexpected-root.zip");
            writeZip(unexpectedRoot, List.of(
                    entry("ringlog-export.xlsx", workbookBytes),
                    entry("notes.txt", new byte[]{1})
            ));
            requireValidationFailure(() -> ImportSource.open(unexpectedRoot), "raíz");

            Path tooMany = directory.resolve("too-many.zip");
            try (OutputStream output = Files.newOutputStream(tooMany);
                 ZipOutputStream zip = new ZipOutputStream(output)) {
                put(zip, "ringlog-export.xlsx", workbookBytes);
                for (int index = 0; index <= ImportSource.MAX_ARCHIVE_ENTRIES; index++) {
                    put(zip, "photos/entry-" + index + ".bin", new byte[0]);
                }
            }
            requireValidationFailure(() -> ImportSource.open(tooMany), "entradas");
        } finally {
            deleteDirectory(directory);
        }
    }

    private static Map<String, String> metadata(
            String format,
            String version,
            String extraKey,
            String extraValue
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("format", format);
        values.put("format_version", version);
        if (extraKey != null) {
            values.put(extraKey, extraValue);
        }
        return values;
    }

    private static Path writeProfile(
            Path output,
            Map<String, String> metadata,
            Set<String> sheets,
            boolean duplicateFormat,
            boolean formulaFormat
    ) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet metadataSheet = workbook.createSheet("metadata");
            Row header = metadataSheet.createRow(0);
            header.createCell(0).setCellValue("key");
            header.createCell(1).setCellValue("value");
            int rowIndex = 1;
            for (Map.Entry<String, String> value : metadata.entrySet()) {
                Row row = metadataSheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(value.getKey());
                if (formulaFormat && "format".equals(value.getKey())) {
                    row.createCell(1).setCellFormula("\"" + value.getValue() + "\"");
                } else {
                    row.createCell(1).setCellValue(value.getValue());
                }
            }
            if (duplicateFormat) {
                Row row = metadataSheet.createRow(rowIndex);
                row.createCell(0).setCellValue("format");
                row.createCell(1).setCellValue(metadata.get("format"));
            }
            for (String sheet : sheets) {
                if (!"metadata".equals(sheet)) {
                    workbook.createSheet(sheet);
                }
            }
            try (OutputStream stream = Files.newOutputStream(output)) {
                workbook.write(stream);
            }
        }
        return output;
    }

    private static ZipContent entry(String name, byte[] content) {
        return new ZipContent(name, content.clone());
    }

    private static void writeZip(Path output, List<ZipContent> entries) throws IOException {
        try (OutputStream stream = Files.newOutputStream(output);
             ZipOutputStream zip = new ZipOutputStream(stream)) {
            for (ZipContent entry : entries) {
                put(zip, entry.name(), entry.content());
            }
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static void requireValidationFailure(ThrowingAction action, String messageFragment)
            throws Exception {
        try {
            Object closeable = action.run();
            if (closeable instanceof AutoCloseable resource) {
                resource.close();
            }
        } catch (ImportValidationException expected) {
            String message = expected.getMessage() == null
                    ? ""
                    : expected.getMessage().toLowerCase(java.util.Locale.ROOT);
            require(message.contains(messageFragment.toLowerCase(java.util.Locale.ROOT)),
                    "Validation message must explain the problem: " + expected.getMessage());
            return;
        }
        throw new AssertionError("Expected import validation failure containing: " + messageFragment);
    }

    private static void requireUnmodifiable(Map<String, String> values) {
        try {
            values.put("unexpected", "value");
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("WorkbookProfile metadata must be immutable");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record ZipContent(String name, byte[] content) {
        private ZipContent {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        Object run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        detectsOnlyTheThreeApprovedProfiles();
        rejectsUnknownIncompleteAndHybridMetadata();
        opensNativeZipResolvesMediaAndCleansTemporaryFiles();
        rejectsUnsafeOrUnboundedArchives();
        System.out.println("WorkbookFormatDetectorTest: PASS");
    }
}
