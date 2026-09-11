package com.adelylria.ringlog.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.adelylria.ringlog.model.EventType;
import com.adelylria.ringlog.model.view.BirdEventDetail;
import com.adelylria.ringlog.model.view.BirdEventReportRow;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFSheet;

public final class RecordReportServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-08-24T10:15:30Z"),
            ZoneOffset.UTC
    );

    private RecordReportServiceTest() {
    }

    public static void excelContainsTheCompleteFilteredDataset() throws Exception {
        Path directory = Files.createTempDirectory("ringlog-record-report-xlsx-");
        try {
            RecordReportService service = new RecordReportService(FIXED_CLOCK);
            RecordReportResult result = service.export(
                    RecordReportFormat.EXCEL,
                    directory.resolve("federacion"),
                    reportRows(),
                    List.of("Tipo: Control", "Fecha: agosto 2026")
            );

            require(result.path().getFileName().toString().equals("federacion.xlsx"),
                    "Excel should add its extension without asking the user");
            require(result.recordCount() == 2,
                    "The result should report every exported record");
            try (Workbook workbook = WorkbookFactory.create(result.path().toFile())) {
                require(workbook.getNumberOfSheets() == 2,
                        "The workbook should separate its summary from its data");
                require(workbook.getSheet("Resumen") != null,
                        "The workbook should include a readable summary");
                XSSFSheet records = (XSSFSheet) workbook.getSheet("Registros");
                require(records != null, "The workbook should include a records sheet");
                require(records.getPaneInformation() != null,
                        "The records header should stay visible while scrolling");
                require(records.getCTWorksheet().isSetAutoFilter(),
                        "The exported columns should remain filterable in Excel");

                Row header = records.getRow(3);
                Map<String, Integer> columns = headerColumns(header);
                require(columns.keySet().containsAll(List.of(
                                "Fecha", "Hora", "Tipo", "Anilla", "Especie",
                                "Código de especie", "Nombre científico", "Nombre común",
                                "Lugar", "Localidad", "Ubicación libre", "Latitud",
                                "Longitud", "Sexo", "Edad EURING", "Estado",
                                "Estado vital", "Condición", "Reproducción", "Muda",
                                "Extensión de muda", "Vuelta", "Anillador",
                                "Clasificación histórica", "Ala (mm)", "P3 (mm)",
                                "Tarso (mm)", "Peso (g)", "Grasa", "Músculo",
                                "Nubes", "Lluvia", "Sensación térmica (°C)",
                                "Viento", "Observaciones", "N.º de fotos",
                                "Archivo de origen", "Referencia de origen"
                        )),
                        "The federation export should contain every useful field");

                Row first = records.getRow(4);
                require(first.getCell(columns.get("Fecha")).getCellType()
                                == CellType.NUMERIC,
                        "Dates should be sortable date values, not display strings");
                require(first.getCell(columns.get("Peso (g)")).getNumericCellValue()
                                == 17.5,
                        "Measurements should remain numeric");
                require(first.getCell(columns.get("Anilla")).getStringCellValue()
                                .equals("ES000005"),
                        "The first filtered record should keep its ring number");
                require(first.getCell(columns.get("Observaciones")).getStringCellValue()
                                .equals("Hallada junto al carrizal."),
                        "Field notes should be exported verbatim");
                require(first.getCell(columns.get("Sexo")).getStringCellValue()
                                .equals("M · Macho")
                                && first.getCell(columns.get("Estado")).getStringCellValue()
                                        .equals("CORRECT · Correcto"),
                        "Coded fields should keep the federation code and its readable label");
                require(first.getCell(columns.get("N.º de fotos")).getNumericCellValue()
                                == 2,
                        "The report should state how many linked photos exist");
                require(first.getCell(columns.get("Código de especie"))
                                .getStringCellValue().equals("PARMAJ")
                                && first.getCell(columns.get("Nombre científico"))
                                        .getStringCellValue().equals("Parus major")
                                && first.getCell(columns.get("Nombre común"))
                                        .getStringCellValue().equals("Carbonero común"),
                        "The report should preserve the complete species identity");
                require(first.getCell(columns.get("Archivo de origen"))
                                .getStringCellValue().equals("capturas-legado.xlsx")
                                && first.getCell(columns.get("Referencia de origen"))
                                        .getStringCellValue().equals("Hoja1!5"),
                        "The report should retain imported-record provenance");
                require(first.getCell(columns.get("Latitud")).getCellStyle()
                                .getDataFormatString().contains("00000")
                                && first.getCell(columns.get("N.º de fotos")).getCellStyle()
                                        .getDataFormatString().equals("0"),
                        "Coordinates and integer counts should use appropriate formats");
                Row second = records.getRow(5);
                require(second.getCell(columns.get("Anilla"))
                                .getStringCellValue().equals("V6954"),
                        "Pagination must not omit the second filtered record");
                require(second.getCell(columns.get("Ala (mm)"))
                                .getCellType() == CellType.BLANK,
                        "Missing numeric data should remain blank for analysis");
                require(second.getCell(columns.get("Observaciones"))
                                .getCellType() == CellType.BLANK,
                        "Missing notes should be a truly blank Excel cell");

                String summary = sheetText(workbook.getSheet("Resumen"));
                require(summary.contains("Tipo: Control")
                                && summary.contains("Fecha: agosto 2026")
                                && summary.contains("2"),
                        "The summary should explain the filters and result count");
            }
        } finally {
            deleteTree(directory);
        }
    }

    public static void pdfStartsEachRecordOnItsOwnPage() throws Exception {
        Path directory = Files.createTempDirectory("ringlog-record-report-pdf-");
        try {
            RecordReportService service = new RecordReportService(FIXED_CLOCK);
            RecordReportResult result = service.export(
                    RecordReportFormat.PDF,
                    directory.resolve("informe-federacion"),
                    reportRows(),
                    List.of("Lugar: ELS RAFALS")
            );

            require(result.path().getFileName().toString()
                            .equals("informe-federacion.pdf"),
                    "PDF should add its extension without asking the user");
            try (PDDocument document = Loader.loadPDF(result.path().toFile())) {
                require(document.getNumberOfPages() == 3,
                        "A short report should have one cover and one page per record");
                String cover = pageText(document, 1);
                String first = pageText(document, 2);
                String second = pageText(document, 3);
                require(cover.contains("Informe de registros")
                                && cover.contains("Lugar: ELS RAFALS")
                                && cover.contains("2 registros"),
                        "The cover should summarize the exported selection");
                require(first.contains("ES000005")
                                && first.contains("Carbonero común")
                                && first.contains("Parus major")
                                && first.contains("PARMAJ")
                                && first.contains("capturas-legado.xlsx")
                                && first.contains("Hoja1!5")
                                && first.contains("17.5 g")
                                && first.contains("Borde del carrizal")
                                && first.contains("Hallada junto al carrizal."),
                        "The first record page should contain its complete readable data");
                require(!first.contains("V6954"),
                        "A record must not start on the previous record's page");
                require(second.contains("V6954")
                                && second.contains("TURDUS PHILOMELOS"),
                        "The second record should begin on its own page");
            }
        } finally {
            deleteTree(directory);
        }
    }

    public static void failedExportPreservesAnExistingFile() throws Exception {
        Path directory = Files.createTempDirectory("ringlog-record-report-atomic-");
        try {
            Path destination = directory.resolve("informe.xlsx");
            byte[] original = "copia anterior".getBytes(StandardCharsets.UTF_8);
            Files.write(destination, original);

            RecordReportService service = new RecordReportService(FIXED_CLOCK);
            requireThrows(() -> service.export(
                    RecordReportFormat.EXCEL,
                    destination,
                    List.of(),
                    List.of()
            ), "An empty filtered result should not replace a previous report");
            require(java.util.Arrays.equals(Files.readAllBytes(destination), original),
                    "A failed export must leave the previous file untouched");
        } finally {
            deleteTree(directory);
        }
    }

    public static void aDestinationChangedDuringRenderingIsNeverOverwritten()
            throws Exception {
        Path directory = Files.createTempDirectory("ringlog-record-report-race-");
        try {
            Path destination = directory.resolve("informe.xlsx");
            Files.writeString(destination, "versión confirmada", StandardCharsets.UTF_8);
            byte[] externalChange = "cambio externo posterior"
                    .getBytes(StandardCharsets.UTF_8);
            RecordReportService service = new RecordReportService(
                    FIXED_CLOCK,
                    path -> {
                        try {
                            Files.write(path, externalChange);
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    }
            );

            requireThrows(() -> service.export(
                    RecordReportFormat.EXCEL,
                    destination,
                    reportRows(),
                    List.of()
            ), "A file changed after confirmation must not be replaced");
            require(java.util.Arrays.equals(Files.readAllBytes(destination), externalChange),
                    "The export should preserve the file version written concurrently");
        } finally {
            deleteTree(directory);
        }
    }

    public static void excelPreservesLongNotesAcrossSafeColumns() throws Exception {
        Path directory = Files.createTempDirectory("ringlog-record-report-long-text-");
        try {
            String notes = "a".repeat(32_766) + "🐦" + " final".repeat(1_500);
            BirdEventReportRow record = withObservations(reportRows().get(0), notes);
            RecordReportResult result = new RecordReportService(FIXED_CLOCK).export(
                    RecordReportFormat.EXCEL,
                    directory.resolve("notas-largas.xlsx"),
                    List.of(record),
                    List.of()
            );

            try (Workbook workbook = WorkbookFactory.create(result.path().toFile())) {
                Sheet sheet = workbook.getSheet("Registros");
                Row header = sheet.getRow(3);
                Row data = sheet.getRow(4);
                StringBuilder restored = new StringBuilder();
                for (Cell cell : header) {
                    if (cell.getStringCellValue().startsWith("Observaciones")) {
                        restored.append(data.getCell(cell.getColumnIndex())
                                .getStringCellValue());
                    }
                }
                require(restored.toString().equals(notes),
                        "Long notes should be split without losing Unicode text");
            }
        } finally {
            deleteTree(directory);
        }
    }

    public static void pdfPreservesUnicodeInDynamicFields() throws Exception {
        Path directory = Files.createTempDirectory("ringlog-record-report-unicode-");
        try {
            BirdEventReportRow original = reportRows().get(0);
            BirdEventDetail unicodeDetail = withSpeciesAndLocation(
                    original.detail(),
                    "Σπίνος Ω",
                    "Χώρα Ω"
            );
            BirdEventReportRow record = new BirdEventReportRow(
                    unicodeDetail,
                    "Ω-1",
                    "Σπίνος Ω",
                    "Πουλί Ω",
                    "πηγή Ω.xlsx",
                    "αναφορά Ω",
                    original.photoCount()
            );
            RecordReportResult result = new RecordReportService(FIXED_CLOCK).export(
                    RecordReportFormat.PDF,
                    directory.resolve("unicode.pdf"),
                    List.of(record),
                    List.of()
            );
            try (PDDocument document = Loader.loadPDF(result.path().toFile())) {
                String text = new PDFTextStripper().getText(document);
                require(text.contains("Σπίνος Ω")
                                && text.contains("Χώρα Ω")
                                && text.contains("πηγή Ω.xlsx")
                                && text.contains("αναφορά Ω"),
                        "The PDF should preserve Unicode in every dynamic field");
            }
        } finally {
            deleteTree(directory);
        }
    }

    public static void pdfPreservesParagraphBreaksInImportedNotes() throws Exception {
        Path directory = Files.createTempDirectory("ringlog-record-report-paragraphs-");
        try {
            BirdEventReportRow record = withObservations(
                    reportRows().get(0),
                    "Primera observación importada.\n"
                            + "Segunda observación pendiente.\r\n"
                            + "Última línea comprobada."
            );
            RecordReportResult result = new RecordReportService(FIXED_CLOCK).export(
                    RecordReportFormat.PDF,
                    directory.resolve("notas-con-parrafos.pdf"),
                    List.of(record),
                    List.of("Estado: Revisar")
            );

            try (PDDocument document = Loader.loadPDF(result.path().toFile())) {
                String text = new PDFTextStripper().getText(document);
                require(text.contains("Primera observación importada.")
                                && text.contains("Segunda observación pendiente.")
                                && text.contains("Última línea comprobada."),
                        "The PDF must preserve every paragraph from imported field notes");
            }
        } finally {
            deleteTree(directory);
        }
    }

    public static void pdfNeverDropsLongFieldsOrFieldNotes() throws Exception {
        Path directory = Files.createTempDirectory("ringlog-record-report-long-pdf-");
        try {
            BirdEventReportRow original = reportRows().get(0);
            String longLocation = "UBICACION" + "x".repeat(900) + "FINALUBICACION";
            String longNotes = ("Línea completa de observación. ".repeat(80))
                    + "FINALNOTAS";
            BirdEventDetail detail = withSpeciesAndLocation(
                    withObservations(original.detail(), longNotes),
                    original.species(),
                    longLocation
            );
            BirdEventReportRow record = new BirdEventReportRow(
                    detail,
                    original.speciesCode(),
                    original.speciesScientificName(),
                    original.speciesCommonName(),
                    original.sourceName(),
                    "REFERENCIA" + "z".repeat(700) + "FINALREFERENCIA",
                    original.photoCount()
            );

            RecordReportResult result = new RecordReportService(FIXED_CLOCK).export(
                    RecordReportFormat.PDF,
                    directory.resolve("campos-largos.pdf"),
                    List.of(record),
                    List.of()
            );
            try (PDDocument document = Loader.loadPDF(result.path().toFile())) {
                String compact = new PDFTextStripper().getText(document)
                        .replaceAll("\\s+", "");
                require(document.getNumberOfPages() > 2,
                        "Long record data should continue on additional pages");
                require(compact.contains(longLocation)
                                && compact.contains("FINALREFERENCIA")
                                && compact.contains("FINALNOTAS"),
                        "No user-entered field may be truncated in the PDF");
            }
        } finally {
            deleteTree(directory);
        }
    }

    public static void reportLimitsAreFormatSpecificAndCheckedBeforeWriting()
            throws Exception {
        Path directory = Files.createTempDirectory("ringlog-record-report-limit-");
        try {
            require(RecordReportFormat.PDF.maximumRecords()
                            < RecordReportFormat.EXCEL.maximumRecords(),
                    "A page-per-record PDF should have a lower safe limit than Excel");
            List<BirdEventReportRow> records = java.util.Collections.nCopies(
                    RecordReportFormat.PDF.maximumRecords() + 1,
                    reportRows().get(0)
            );
            Path destination = directory.resolve("demasiado-grande.pdf");
            requireThrows(() -> new RecordReportService(FIXED_CLOCK).export(
                    RecordReportFormat.PDF,
                    destination,
                    records,
                    List.of()
            ), "Oversized reports should be rejected before creating a file");
            require(!Files.exists(destination),
                    "An unsafe report size must not leave a partial file");
        } finally {
            deleteTree(directory);
        }
    }

    private static List<BirdEventReportRow> reportRows() {
        List<BirdEventDetail> details = details();
        return List.of(
                new BirdEventReportRow(
                        details.get(0),
                        "PARMAJ",
                        "Parus major",
                        "Carbonero común",
                        "capturas-legado.xlsx",
                        "Hoja1!5",
                        2
                ),
                new BirdEventReportRow(
                        details.get(details.size() - 1),
                        "TURPHI",
                        "Turdus philomelos",
                        null,
                        null,
                        null,
                        0
                )
        );
    }

    private static List<BirdEventDetail> details() {
        BirdEventDetail first = new BirdEventDetail(
                5,
                2,
                "ES000005",
                "Carbonero común",
                EventType.CONTROL,
                "2026-08-22",
                "08:55:00",
                "ELS RAFALS",
                "POLLENSA",
                "Borde del carrizal",
                "MIST_NET",
                "M",
                "5",
                1,
                3,
                "AR",
                "CORRECT",
                "NONE",
                "LOW",
                "PARTIAL",
                "GOOD",
                "RETURN",
                73.8,
                49.6,
                30.1,
                17.5,
                "MEDIUM",
                "NO",
                "23.8",
                "LIGHT",
                39.75,
                2.8,
                "Hallada junto al carrizal.",
                false,
                List.of("foto-1.jpg", "foto-2.jpg")
        );
        BirdEventDetail second = new BirdEventDetail(
                6,
                3,
                "V6954",
                "TURDUS PHILOMELOS",
                EventType.CONTROL,
                "2026-08-23",
                "18:35:00",
                "ELS RAFALS",
                "POLLENSA",
                null,
                null,
                "U",
                "4",
                2,
                2,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                71.0,
                null,
                null,
                null,
                null,
                39.85,
                2.98,
                null,
                false,
                List.<String>of()
        );
        return List.of(first, second);
    }

    private static BirdEventDetail withObservations(
            BirdEventDetail source,
            String observations
    ) {
        return new BirdEventDetail(
                source.id(),
                source.birdId(),
                source.ringNumber(),
                source.species(),
                source.eventType(),
                source.eventDate(),
                source.eventTime(),
                source.place(),
                source.locality(),
                source.locationText(),
                source.captureType(),
                source.sexCode(),
                source.ageEuringCode(),
                source.fatScore(),
                source.muscleScore(),
                source.ringerInitials(),
                source.status(),
                source.reproductiveStatus(),
                source.moultIntensity(),
                source.moultExtension(),
                source.birdCondition(),
                source.returnStatus(),
                source.wing(),
                source.p3(),
                source.torso(),
                source.weight(),
                source.clouds(),
                source.rain(),
                source.thermalSensation(),
                source.wind(),
                source.latitude(),
                source.longitude(),
                observations,
                source.dead(),
                source.photoPaths()
        );
    }

    private static BirdEventReportRow withObservations(
            BirdEventReportRow source,
            String observations
    ) {
        return new BirdEventReportRow(
                withObservations(source.detail(), observations),
                source.speciesCode(),
                source.speciesScientificName(),
                source.speciesCommonName(),
                source.sourceName(),
                source.sourceReference(),
                source.photoCount()
        );
    }

    private static BirdEventDetail withSpeciesAndLocation(
            BirdEventDetail source,
            String species,
            String location
    ) {
        return new BirdEventDetail(
                source.id(),
                source.birdId(),
                source.ringNumber(),
                species,
                source.eventType(),
                source.eventDate(),
                source.eventTime(),
                source.place(),
                source.locality(),
                location,
                source.captureType(),
                source.sexCode(),
                source.ageEuringCode(),
                source.fatScore(),
                source.muscleScore(),
                source.ringerInitials(),
                source.status(),
                source.reproductiveStatus(),
                source.moultIntensity(),
                source.moultExtension(),
                source.birdCondition(),
                source.returnStatus(),
                source.wing(),
                source.p3(),
                source.torso(),
                source.weight(),
                source.clouds(),
                source.rain(),
                source.thermalSensation(),
                source.wind(),
                source.latitude(),
                source.longitude(),
                source.observations(),
                source.dead(),
                source.photoPaths()
        );
    }

    private static Map<String, Integer> headerColumns(Row row) {
        Map<String, Integer> columns = new HashMap<>();
        for (Cell cell : row) {
            columns.put(cell.getStringCellValue(), cell.getColumnIndex());
        }
        return columns;
    }

    private static String sheetText(Sheet sheet) {
        StringBuilder text = new StringBuilder();
        for (Row row : sheet) {
            for (Cell cell : row) {
                switch (cell.getCellType()) {
                    case STRING -> text.append(cell.getStringCellValue());
                    case NUMERIC -> text.append(cell.getNumericCellValue());
                    default -> {
                    }
                }
                text.append(' ');
            }
        }
        return text.toString();
    }

    private static String pageText(PDDocument document, int page) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(document);
    }

    private static void requireThrows(ThrowingRunnable action, String message)
            throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (RecordReportException expected) {
            // Expected behavior.
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        excelContainsTheCompleteFilteredDataset();
        pdfStartsEachRecordOnItsOwnPage();
        failedExportPreservesAnExistingFile();
        aDestinationChangedDuringRenderingIsNeverOverwritten();
        excelPreservesLongNotesAcrossSafeColumns();
        pdfPreservesUnicodeInDynamicFields();
        pdfPreservesParagraphBreaksInImportedNotes();
        pdfNeverDropsLongFieldsOrFieldNotes();
        reportLimitsAreFormatSpecificAndCheckedBeforeWriting();
        System.out.println("RecordReportServiceTest: PASS");
    }
}
