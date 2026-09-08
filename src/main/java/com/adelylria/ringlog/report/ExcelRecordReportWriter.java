package com.adelylria.ringlog.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.adelylria.ringlog.model.view.BirdEventReportRow;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

final class ExcelRecordReportWriter {

    private static final int HEADER_ROW = 3;
    private static final int FIRST_DATA_ROW = HEADER_ROW + 1;
    private static final int EXCEL_TEXT_LIMIT = 32_767;
    private static final int EXCEL_TEXT_CHUNK = 32_000;
    private static final int MAX_OBSERVATION_CHUNKS = 32;
    private static final List<String> BASE_HEADERS = List.of(
            "Fecha",
            "Hora",
            "Tipo",
            "Anilla",
            "Especie",
            "Código de especie",
            "Nombre científico",
            "Nombre común",
            "Lugar",
            "Localidad",
            "Ubicación libre",
            "Latitud",
            "Longitud",
            "Sexo",
            "Edad EURING",
            "Estado",
            "Estado vital",
            "Condición",
            "Reproducción",
            "Muda",
            "Extensión de muda",
            "Vuelta",
            "Anillador",
            "Clasificación histórica",
            "Ala (mm)",
            "P3 (mm)",
            "Torso (mm)",
            "Peso (g)",
            "Grasa",
            "Músculo",
            "Nubes",
            "Lluvia",
            "Sensación térmica (°C)",
            "Viento"
    );
    private static final int[] BASE_COLUMN_WIDTHS = {
            14, 9, 17, 17, 29, 17, 28, 26, 23, 21, 31, 14, 14, 13,
            13, 15, 13, 17, 17, 14, 18, 14, 13, 22, 12, 12, 12, 12,
            10, 11, 13, 11, 20, 13
    };

    void write(
            Path destination,
            List<BirdEventReportRow> records,
            List<String> filters,
            LocalDateTime generatedAt
    ) throws IOException, RecordReportException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            WorkbookStyles styles = new WorkbookStyles(workbook);
            writeSummary(workbook, records, filters, generatedAt, styles);
            writeRecords(workbook, records, filters, generatedAt, styles);
            try (OutputStream output = Files.newOutputStream(destination)) {
                workbook.write(output);
            }
        } catch (IllegalArgumentException exception) {
            throw new RecordReportException(
                    "Hay un texto demasiado largo para incluirlo en una celda de Excel.",
                    exception
            );
        }
    }

    static void validate(Path workbookFile, int expectedRecords)
            throws IOException, RecordReportException {
        if (!Files.isRegularFile(workbookFile) || Files.size(workbookFile) == 0) {
            throw new RecordReportException("El Excel generado está vacío.");
        }
        try (InputStream input = Files.newInputStream(workbookFile);
             Workbook workbook = WorkbookFactory.create(input)) {
            Sheet summary = workbook.getSheet("Resumen");
            Sheet records = workbook.getSheet("Registros");
            if (summary == null || records == null
                    || records.getRow(HEADER_ROW) == null
                    || records.getLastRowNum() != HEADER_ROW + expectedRecords) {
                throw new RecordReportException(
                        "El Excel generado no coincide con los registros seleccionados."
                );
            }
        } catch (RecordReportException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RecordReportException(
                    "No se pudo verificar el Excel generado.",
                    exception
            );
        }
    }

    private static void writeSummary(
            XSSFWorkbook workbook,
            List<BirdEventReportRow> records,
            List<String> filters,
            LocalDateTime generatedAt,
            WorkbookStyles styles
    ) {
        Sheet sheet = workbook.createSheet("Resumen");
        sheet.setDisplayGridlines(false);
        sheet.setPrintGridlines(false);
        sheet.setColumnWidth(0, 24 * 256);
        sheet.setColumnWidth(1, 22 * 256);
        sheet.setColumnWidth(2, 4 * 256);
        sheet.setColumnWidth(3, 20 * 256);
        sheet.setColumnWidth(4, 18 * 256);
        sheet.setColumnWidth(5, 4 * 256);
        sheet.setColumnWidth(6, 20 * 256);
        sheet.setColumnWidth(7, 22 * 256);

        Row brand = sheet.createRow(0);
        brand.setHeightInPoints(35);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 7));
        Cell brandCell = brand.createCell(0);
        brandCell.setCellValue("RingLog");
        brandCell.setCellStyle(styles.title());

        Row title = sheet.createRow(1);
        title.setHeightInPoints(28);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 7));
        Cell titleCell = title.createCell(0);
        titleCell.setCellValue("Informe de registros para la federación");
        titleCell.setCellStyle(styles.subtitle());

        summaryValue(sheet, 3, 0, "Registros incluidos", records.size(), styles);
        summaryValue(
                sheet,
                3,
                3,
                "Fecha inicial",
                records.stream().map(BirdEventReportRow::eventDate)
                        .filter(value -> value != null && !value.isBlank())
                        .min(String::compareTo)
                        .map(RecordReportText::date)
                        .orElse("—"),
                styles
        );
        summaryValue(
                sheet,
                3,
                6,
                "Fecha final",
                records.stream().map(BirdEventReportRow::eventDate)
                        .filter(value -> value != null && !value.isBlank())
                        .max(String::compareTo)
                        .map(RecordReportText::date)
                        .orElse("—"),
                styles
        );

        Row generated = sheet.createRow(5);
        generated.createCell(0).setCellValue("Generado");
        generated.getCell(0).setCellStyle(styles.label());
        sheet.addMergedRegion(new CellRangeAddress(5, 5, 1, 7));
        Cell generatedValue = generated.createCell(1);
        generatedValue.setCellValue(RecordReportText.generated(generatedAt));
        generatedValue.setCellStyle(styles.value());

        Row filterHeading = sheet.createRow(7);
        sheet.addMergedRegion(new CellRangeAddress(7, 7, 0, 7));
        Cell filterHeadingCell = filterHeading.createCell(0);
        filterHeadingCell.setCellValue("Filtros aplicados");
        filterHeadingCell.setCellStyle(styles.section());
        List<String> visibleFilters = filters.isEmpty()
                ? List.of("Sin filtros · archivo completo")
                : filters;
        for (int index = 0; index < visibleFilters.size(); index++) {
            Row row = sheet.createRow(8 + index);
            sheet.addMergedRegion(new CellRangeAddress(8 + index, 8 + index, 0, 7));
            Cell cell = row.createCell(0);
            cell.setCellValue("• " + excelText(visibleFilters.get(index)));
            cell.setCellStyle(styles.filter());
        }

        int distributionRow = 10 + visibleFilters.size();
        Row distributionHeading = sheet.createRow(distributionRow);
        sheet.addMergedRegion(new CellRangeAddress(
                distributionRow,
                distributionRow,
                0,
                7
        ));
        Cell distributionCell = distributionHeading.createCell(0);
        distributionCell.setCellValue("Distribución por tipo");
        distributionCell.setCellStyle(styles.section());

        Map<String, Long> counts = new LinkedHashMap<>();
        for (BirdEventReportRow record : records) {
            counts.merge(record.eventType().toString(), 1L, Long::sum);
        }
        int rowIndex = distributionRow + 1;
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            Row row = sheet.createRow(rowIndex++);
            Cell label = row.createCell(0);
            label.setCellValue(entry.getKey());
            label.setCellStyle(styles.value());
            Cell value = row.createCell(1);
            value.setCellValue(entry.getValue());
            value.setCellStyle(styles.count());
        }

        sheet.createFreezePane(0, 2);
        sheet.getPrintSetup().setFitWidth((short) 1);
        sheet.setFitToPage(true);
    }

    private static void summaryValue(
            Sheet sheet,
            int rowIndex,
            int column,
            String label,
            Object value,
            WorkbookStyles styles
    ) {
        Row labelRow = sheet.getRow(rowIndex);
        if (labelRow == null) {
            labelRow = sheet.createRow(rowIndex);
        }
        Cell labelCell = labelRow.createCell(column);
        labelCell.setCellValue(label);
        labelCell.setCellStyle(styles.label());
        Cell valueCell = labelRow.createCell(column + 1);
        if (value instanceof Number number) {
            valueCell.setCellValue(number.doubleValue());
            valueCell.setCellStyle(styles.count());
        } else {
            valueCell.setCellValue(excelText(RecordReportText.display(value)));
            valueCell.setCellStyle(styles.value());
        }
    }

    private static void writeRecords(
            XSSFWorkbook workbook,
            List<BirdEventReportRow> records,
            List<String> filters,
            LocalDateTime generatedAt,
            WorkbookStyles styles
    ) {
        int observationColumns = records.stream()
                .mapToInt(record -> observationChunks(record.observations()).size())
                .max()
                .orElse(1);
        List<String> headers = headers(observationColumns);
        Sheet sheet = workbook.createSheet("Registros");
        sheet.setDisplayGridlines(false);
        sheet.setPrintGridlines(false);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, headers.size() - 1));
        Row title = sheet.createRow(0);
        title.setHeightInPoints(31);
        Cell titleCell = title.createCell(0);
        titleCell.setCellValue("RingLog · Informe de registros");
        titleCell.setCellStyle(styles.title());

        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, headers.size() - 1));
        Row subtitle = sheet.createRow(1);
        Cell subtitleCell = subtitle.createCell(0);
        subtitleCell.setCellValue(
                records.size() + (records.size() == 1 ? " registro" : " registros")
                        + " · Generado " + RecordReportText.generated(generatedAt)
        );
        subtitleCell.setCellStyle(styles.subtitle());

        sheet.addMergedRegion(new CellRangeAddress(2, 2, 0, headers.size() - 1));
        Row filterRow = sheet.createRow(2);
        Cell filterCell = filterRow.createCell(0);
        filterCell.setCellValue(filters.isEmpty()
                ? "Sin filtros · archivo completo"
                : excelText(String.join("  |  ", filters)));
        filterCell.setCellStyle(styles.filter());

        Row header = sheet.createRow(HEADER_ROW);
        header.setHeightInPoints(31);
        for (int column = 0; column < headers.size(); column++) {
            Cell cell = header.createCell(column);
            cell.setCellValue(headers.get(column));
            cell.setCellStyle(styles.header());
            int width = column < BASE_COLUMN_WIDTHS.length
                    ? BASE_COLUMN_WIDTHS[column]
                    : column < BASE_COLUMN_WIDTHS.length + observationColumns
                            ? 46
                            : column == BASE_COLUMN_WIDTHS.length + observationColumns
                                    ? 13
                                    : 28;
            sheet.setColumnWidth(column, width * 256);
        }

        for (int index = 0; index < records.size(); index++) {
            writeRecordRow(
                    sheet.createRow(FIRST_DATA_ROW + index),
                    records.get(index),
                    index % 2 == 1,
                    observationColumns,
                    styles
            );
        }

        sheet.createFreezePane(0, FIRST_DATA_ROW);
        sheet.setAutoFilter(new CellRangeAddress(
                HEADER_ROW,
                HEADER_ROW + records.size(),
                0,
                headers.size() - 1
        ));
        sheet.setRepeatingRows(new CellRangeAddress(HEADER_ROW, HEADER_ROW, -1, -1));
        sheet.getPrintSetup().setLandscape(true);
        sheet.getPrintSetup().setFitWidth((short) 1);
        sheet.getPrintSetup().setFitHeight((short) 0);
        sheet.setFitToPage(true);
    }

    private static void writeRecordRow(
            Row row,
            BirdEventReportRow record,
            boolean alternate,
            int observationColumns,
            WorkbookStyles styles
    ) {
        row.setHeightInPoints(30);
        int column = 0;
        writeDate(row, column++, record.eventDate(), alternate, styles);
        writeTime(row, column++, record.eventTime(), alternate, styles);
        writeText(row, column++, record.eventType().toString(), alternate, styles);
        writeText(row, column++, record.ringNumber(), alternate, styles);
        writeText(row, column++, record.species(), alternate, styles);
        writeText(row, column++, record.speciesCode(), alternate, styles);
        writeText(row, column++, record.speciesScientificName(), alternate, styles);
        writeText(row, column++, record.speciesCommonName(), alternate, styles);
        writeText(row, column++, record.place(), alternate, styles);
        writeText(row, column++, record.locality(), alternate, styles);
        writeText(row, column++, record.locationText(), alternate, styles);
        writeCoordinate(row, column++, record.latitude(), alternate, styles);
        writeCoordinate(row, column++, record.longitude(), alternate, styles);
        writeText(row, column++, RecordReportText.coded(record.sexCode()), alternate, styles);
        writeText(row, column++, record.ageEuringCode(), alternate, styles);
        writeText(row, column++, RecordReportText.coded(record.status()), alternate, styles);
        writeText(row, column++, record.dead() ? "Fallecida" : "Viva", alternate, styles);
        writeText(row, column++, RecordReportText.coded(record.birdCondition()), alternate, styles);
        writeText(row, column++, RecordReportText.coded(record.reproductiveStatus()), alternate, styles);
        writeText(row, column++, RecordReportText.coded(record.moultIntensity()), alternate, styles);
        writeText(row, column++, RecordReportText.coded(record.moultExtension()), alternate, styles);
        writeText(row, column++, RecordReportText.coded(record.returnStatus()), alternate, styles);
        writeText(row, column++, record.ringerInitials(), alternate, styles);
        writeText(row, column++, RecordReportText.coded(record.captureType()), alternate, styles);
        writeNumber(row, column++, record.wing(), alternate, styles);
        writeNumber(row, column++, record.p3(), alternate, styles);
        writeNumber(row, column++, record.torso(), alternate, styles);
        writeNumber(row, column++, record.weight(), alternate, styles);
        writeInteger(row, column++, record.fatScore(), alternate, styles);
        writeInteger(row, column++, record.muscleScore(), alternate, styles);
        writeText(row, column++, RecordReportText.coded(record.clouds()), alternate, styles);
        writeText(row, column++, RecordReportText.coded(record.rain()), alternate, styles);
        writeMaybeNumber(row, column++, record.thermalSensation(), alternate, styles);
        writeText(row, column++, RecordReportText.coded(record.wind()), alternate, styles);
        List<String> observations = observationChunks(record.observations());
        for (int index = 0; index < observationColumns; index++) {
            writeRawText(
                    row,
                    column++,
                    index < observations.size() ? observations.get(index) : "",
                    alternate,
                    styles
            );
        }
        writeInteger(row, column++, record.photoCount(), alternate, styles);
        writeText(row, column++, record.sourceName(), alternate, styles);
        writeText(row, column, record.sourceReference(), alternate, styles);
    }

    private static List<String> headers(int observationColumns) {
        List<String> headers = new ArrayList<>(
                BASE_HEADERS.size() + observationColumns + 3
        );
        headers.addAll(BASE_HEADERS);
        for (int index = 0; index < observationColumns; index++) {
            headers.add(index == 0
                    ? "Observaciones"
                    : "Observaciones (" + (index + 1) + ")");
        }
        headers.add("N.º de fotos");
        headers.add("Archivo de origen");
        headers.add("Referencia de origen");
        return List.copyOf(headers);
    }

    private static List<String> observationChunks(String value) {
        if (value == null || value.isEmpty()) {
            return List.of("");
        }
        List<String> chunks = new ArrayList<>();
        int offset = 0;
        while (offset < value.length()) {
            if (chunks.size() >= MAX_OBSERVATION_CHUNKS) {
                throw new IllegalArgumentException(
                        "Las observaciones superan el tamaño máximo del informe"
                );
            }
            int end = Math.min(value.length(), offset + EXCEL_TEXT_CHUNK);
            if (end < value.length()
                    && Character.isHighSurrogate(value.charAt(end - 1))
                    && Character.isLowSurrogate(value.charAt(end))) {
                end--;
            }
            chunks.add(value.substring(offset, end));
            offset = end;
        }
        return List.copyOf(chunks);
    }

    private static void writeDate(
            Row row,
            int column,
            String value,
            boolean alternate,
            WorkbookStyles styles
    ) {
        Cell cell = row.createCell(column);
        try {
            cell.setCellValue(LocalDate.parse(value).atStartOfDay());
            cell.setCellStyle(alternate ? styles.alternateDate() : styles.date());
        } catch (NullPointerException | DateTimeParseException exception) {
            if (value != null && !value.isBlank()) {
                cell.setCellValue(excelText(value));
            }
            cell.setCellStyle(alternate ? styles.alternateBody() : styles.body());
        }
    }

    private static void writeTime(
            Row row,
            int column,
            String value,
            boolean alternate,
            WorkbookStyles styles
    ) {
        Cell cell = row.createCell(column);
        try {
            LocalTime time = LocalTime.parse(value);
            cell.setCellValue(time.toSecondOfDay() / 86_400.0);
            cell.setCellStyle(alternate ? styles.alternateTime() : styles.time());
        } catch (NullPointerException | DateTimeParseException exception) {
            if (value != null && !value.isBlank()) {
                cell.setCellValue(excelText(value));
            }
            cell.setCellStyle(alternate ? styles.alternateBody() : styles.body());
        }
    }

    private static void writeText(
            Row row,
            int column,
            Object value,
            boolean alternate,
        WorkbookStyles styles
    ) {
        Cell cell = row.createCell(column);
        if (value != null && !value.toString().isBlank()) {
            cell.setCellValue(excelText(value.toString()));
        }
        cell.setCellStyle(alternate ? styles.alternateBody() : styles.body());
    }

    private static void writeRawText(
            Row row,
            int column,
            String value,
            boolean alternate,
            WorkbookStyles styles
    ) {
        Cell cell = row.createCell(column);
        if (value != null && !value.isEmpty()) {
            cell.setCellValue(excelText(value));
        }
        cell.setCellStyle(alternate ? styles.alternateBody() : styles.body());
    }

    private static void writeNumber(
            Row row,
            int column,
            Number value,
            boolean alternate,
            WorkbookStyles styles
    ) {
        Cell cell = row.createCell(column);
        if (value == null) {
            cell.setCellStyle(alternate ? styles.alternateNumber() : styles.number());
        } else {
            cell.setCellValue(value.doubleValue());
            cell.setCellStyle(alternate ? styles.alternateNumber() : styles.number());
        }
    }

    private static void writeCoordinate(
            Row row,
            int column,
            Number value,
            boolean alternate,
            WorkbookStyles styles
    ) {
        writeStyledNumber(
                row,
                column,
                value,
                alternate ? styles.alternateCoordinate() : styles.coordinate()
        );
    }

    private static void writeInteger(
            Row row,
            int column,
            Number value,
            boolean alternate,
            WorkbookStyles styles
    ) {
        writeStyledNumber(
                row,
                column,
                value,
                alternate ? styles.alternateInteger() : styles.integer()
        );
    }

    private static void writeStyledNumber(
            Row row,
            int column,
            Number value,
            CellStyle style
    ) {
        Cell cell = row.createCell(column);
        if (value != null) {
            cell.setCellValue(value.doubleValue());
        }
        cell.setCellStyle(style);
    }

    private static void writeMaybeNumber(
            Row row,
            int column,
            String value,
            boolean alternate,
            WorkbookStyles styles
    ) {
        if (value != null) {
            try {
                writeNumber(row, column, Double.parseDouble(value), alternate, styles);
                return;
            } catch (NumberFormatException ignored) {
                // Keep non-numeric historical values as text.
            }
        }
        writeText(row, column, value, alternate, styles);
    }

    private static String excelText(String value) {
        String text = value == null ? "—" : value;
        if (text.length() > EXCEL_TEXT_LIMIT) {
            throw new IllegalArgumentException("Texto mayor que el límite de Excel");
        }
        return text;
    }

    private static final class WorkbookStyles {

        private static final byte[] DARK = {(byte) 25, (byte) 38, (byte) 31};
        private static final byte[] GREEN = {(byte) 55, (byte) 116, (byte) 80};
        private static final byte[] PALE = {(byte) 230, (byte) 241, (byte) 233};
        private static final byte[] ALT = {(byte) 246, (byte) 249, (byte) 246};
        private static final byte[] BORDER = {(byte) 211, (byte) 220, (byte) 213};

        private final CellStyle title;
        private final CellStyle subtitle;
        private final CellStyle section;
        private final CellStyle label;
        private final CellStyle value;
        private final CellStyle count;
        private final CellStyle filter;
        private final CellStyle header;
        private final CellStyle body;
        private final CellStyle alternateBody;
        private final CellStyle number;
        private final CellStyle alternateNumber;
        private final CellStyle coordinate;
        private final CellStyle alternateCoordinate;
        private final CellStyle integer;
        private final CellStyle alternateInteger;
        private final CellStyle date;
        private final CellStyle alternateDate;
        private final CellStyle time;
        private final CellStyle alternateTime;

        WorkbookStyles(XSSFWorkbook workbook) {
            title = workbook.createCellStyle();
            title.setFillForegroundColor(new XSSFColor(DARK, null));
            title.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            title.setVerticalAlignment(VerticalAlignment.CENTER);
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 20);
            titleFont.setColor(IndexedColors.WHITE.getIndex());
            title.setFont(titleFont);

            subtitle = workbook.createCellStyle();
            subtitle.setVerticalAlignment(VerticalAlignment.CENTER);
            XSSFFont subtitleFont = workbook.createFont();
            subtitleFont.setBold(true);
            subtitleFont.setFontHeightInPoints((short) 13);
            subtitleFont.setColor(new XSSFColor(GREEN, null));
            subtitle.setFont(subtitleFont);

            section = workbook.createCellStyle();
            section.setFillForegroundColor(new XSSFColor(PALE, null));
            section.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            section.setVerticalAlignment(VerticalAlignment.CENTER);
            XSSFFont sectionFont = workbook.createFont();
            sectionFont.setBold(true);
            sectionFont.setColor(new XSSFColor(DARK, null));
            section.setFont(sectionFont);

            label = workbook.createCellStyle();
            XSSFFont labelFont = workbook.createFont();
            labelFont.setBold(true);
            labelFont.setColor(new XSSFColor(GREEN, null));
            label.setFont(labelFont);

            value = workbook.createCellStyle();
            value.setVerticalAlignment(VerticalAlignment.CENTER);

            count = workbook.createCellStyle();
            count.cloneStyleFrom(value);
            count.setAlignment(HorizontalAlignment.RIGHT);
            count.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
            XSSFFont countFont = workbook.createFont();
            countFont.setBold(true);
            countFont.setColor(new XSSFColor(DARK, null));
            count.setFont(countFont);

            filter = workbook.createCellStyle();
            filter.setWrapText(true);
            XSSFFont filterFont = workbook.createFont();
            filterFont.setColor(new XSSFColor(GREEN, null));
            filter.setFont(filterFont);

            header = workbook.createCellStyle();
            header.setFillForegroundColor(new XSSFColor(GREEN, null));
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.LEFT);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setWrapText(true);
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            header.setFont(headerFont);

            body = dataStyle(workbook, null);
            alternateBody = dataStyle(workbook, ALT);
            number = numberStyle(workbook, body, "0.0##");
            alternateNumber = numberStyle(workbook, alternateBody, "0.0##");
            coordinate = numberStyle(workbook, body, "0.00000");
            alternateCoordinate = numberStyle(workbook, alternateBody, "0.00000");
            integer = numberStyle(workbook, body, "0");
            alternateInteger = numberStyle(workbook, alternateBody, "0");
            date = numberStyle(workbook, body, "dd mmm yyyy");
            alternateDate = numberStyle(workbook, alternateBody, "dd mmm yyyy");
            time = numberStyle(workbook, body, "hh:mm");
            alternateTime = numberStyle(workbook, alternateBody, "hh:mm");
        }

        private static CellStyle dataStyle(XSSFWorkbook workbook, byte[] fill) {
            XSSFCellStyle style = workbook.createCellStyle();
            style.setVerticalAlignment(VerticalAlignment.TOP);
            style.setWrapText(true);
            style.setBottomBorderColor(new XSSFColor(BORDER, null));
            style.setBorderBottom(BorderStyle.THIN);
            if (fill != null) {
                style.setFillForegroundColor(new XSSFColor(fill, null));
                style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            return style;
        }

        private static CellStyle numberStyle(
                XSSFWorkbook workbook,
                CellStyle base,
                String format
        ) {
            CellStyle style = workbook.createCellStyle();
            style.cloneStyleFrom(base);
            style.setAlignment(HorizontalAlignment.RIGHT);
            style.setDataFormat(workbook.createDataFormat().getFormat(format));
            return style;
        }

        CellStyle title() {
            return title;
        }

        CellStyle subtitle() {
            return subtitle;
        }

        CellStyle section() {
            return section;
        }

        CellStyle label() {
            return label;
        }

        CellStyle value() {
            return value;
        }

        CellStyle count() {
            return count;
        }

        CellStyle filter() {
            return filter;
        }

        CellStyle header() {
            return header;
        }

        CellStyle body() {
            return body;
        }

        CellStyle alternateBody() {
            return alternateBody;
        }

        CellStyle number() {
            return number;
        }

        CellStyle alternateNumber() {
            return alternateNumber;
        }

        CellStyle coordinate() {
            return coordinate;
        }

        CellStyle alternateCoordinate() {
            return alternateCoordinate;
        }

        CellStyle integer() {
            return integer;
        }

        CellStyle alternateInteger() {
            return alternateInteger;
        }

        CellStyle date() {
            return date;
        }

        CellStyle alternateDate() {
            return alternateDate;
        }

        CellStyle time() {
            return time;
        }

        CellStyle alternateTime() {
            return alternateTime;
        }
    }
}
