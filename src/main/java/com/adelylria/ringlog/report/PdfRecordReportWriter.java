package com.adelylria.ringlog.report;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;

import com.adelylria.ringlog.model.view.BirdEventReportRow;

final class PdfRecordReportWriter {

    private static final Color DARK = new Color(25, 38, 31);
    private static final Color GREEN = new Color(55, 116, 80);
    private static final Color ACCENT = new Color(147, 197, 165);
    private static final Color PALE = new Color(238, 244, 239);
    private static final Color PAPER = new Color(250, 249, 246);
    private static final Color MUTED = new Color(91, 105, 96);
    private static final Color BORDER = new Color(211, 220, 213);
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float MARGIN = 42f;
    private static final float CONTENT_WIDTH = PAGE_WIDTH - (MARGIN * 2);

    void write(
            Path destination,
            List<BirdEventReportRow> records,
            List<String> filters,
        LocalDateTime generatedAt
    ) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDFont regular = loadRegularFont(document);
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            List<String> footerLabels = new ArrayList<>();

            configureMetadata(document, records.size(), generatedAt);
            addCover(document, regular, bold, records, filters, generatedAt);
            footerLabels.add("Resumen");
            for (int index = 0; index < records.size(); index++) {
                int pagesBefore = document.getNumberOfPages();
                addRecordPages(
                        document,
                        regular,
                        bold,
                        records.get(index),
                        index + 1,
                        records.size()
                );
                int pagesAdded = document.getNumberOfPages() - pagesBefore;
                for (int page = 0; page < pagesAdded; page++) {
                    footerLabels.add(
                            "Registro " + (index + 1) + " de " + records.size()
                    );
                }
            }
            appendFooters(document, regular, footerLabels);
            document.save(destination.toFile());
        }
    }

    static void validate(Path pdfFile, int expectedRecords)
            throws IOException, RecordReportException {
        if (!Files.isRegularFile(pdfFile) || Files.size(pdfFile) == 0) {
            throw new RecordReportException("El PDF generado está vacío.");
        }
        try (PDDocument document = Loader.loadPDF(pdfFile.toFile())) {
            if (document.isEncrypted()
                    || document.getNumberOfPages() < expectedRecords + 1) {
                throw new RecordReportException(
                        "El PDF generado no coincide con los registros seleccionados."
                );
            }
            String text = new PDFTextStripper().getText(document)
                    .replaceAll("\\s+", " ")
                    .toLowerCase(Locale.ROOT);
            if (!text.contains("ringlog") || !text.contains("informe de registros")) {
                throw new RecordReportException(
                        "No se pudo verificar el contenido del PDF generado."
                );
            }
        } catch (RecordReportException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RecordReportException(
                    "No se pudo verificar el PDF generado.",
                    exception
            );
        }
    }

    private static void configureMetadata(
            PDDocument document,
            int recordCount,
            LocalDateTime generatedAt
    ) {
        PDDocumentInformation information = document.getDocumentInformation();
        information.setTitle("RingLog - Informe de registros");
        information.setSubject(recordCount + " registros de campo");
        information.setAuthor("RingLog");
        information.setCreator("RingLog");
        information.setCustomMetadataValue(
                "Generado",
                RecordReportText.generated(generatedAt)
        );
    }

    private static void addCover(
            PDDocument document,
            PDFont regular,
            PDFont bold,
            List<BirdEventReportRow> records,
            List<String> filters,
            LocalDateTime generatedAt
    ) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            fillPage(content, PAPER);
            fillRect(content, 0, PAGE_HEIGHT - 10, PAGE_WIDTH, 10, GREEN);
            text(content, bold, 13, GREEN, MARGIN, 792, "RINGLOG · DIARIO DE CAMPO");
            text(content, bold, 28, DARK, MARGIN, 738, "Informe de registros");
            text(
                    content,
                    regular,
                    11,
                    MUTED,
                    MARGIN,
                    714,
                    "Selección preparada para compartir con la federación."
            );

            float cardWidth = (CONTENT_WIDTH - 18) / 2;
            coverCard(
                    content,
                    regular,
                    bold,
                    MARGIN,
                    625,
                    cardWidth,
                    72,
                    "REGISTROS INCLUIDOS",
                    records.size() + (records.size() == 1 ? " registro" : " registros")
            );
            coverCard(
                    content,
                    regular,
                    bold,
                    MARGIN + cardWidth + 18,
                    625,
                    cardWidth,
                    72,
                    "PERIODO",
                    dateRange(records)
            );

            float filterTop = 575;
            List<String> visibleFilters = filters.isEmpty()
                    ? List.of("Sin filtros - archivo completo")
                    : filters;
            int lines = 0;
            for (String filter : visibleFilters) {
                lines += Math.max(
                        1,
                        wrap(regular, 10, filter, CONTENT_WIDTH - 32).size()
                );
            }
            float filterHeight = Math.max(104, 55 + lines * 15f);
            fillRect(
                    content,
                    MARGIN,
                    filterTop - filterHeight,
                    CONTENT_WIDTH,
                    filterHeight,
                    Color.WHITE
            );
            strokeRect(
                    content,
                    MARGIN,
                    filterTop - filterHeight,
                    CONTENT_WIDTH,
                    filterHeight,
                    BORDER
            );
            text(content, bold, 11, GREEN, MARGIN + 16, filterTop - 25,
                    "FILTROS APLICADOS");
            float y = filterTop - 49;
            for (String filter : visibleFilters) {
                for (String line : wrap(regular, 10, "• " + filter, CONTENT_WIDTH - 32)) {
                    text(content, regular, 10, DARK, MARGIN + 16, y, line);
                    y -= 15;
                }
            }

            float noteY = Math.min(filterTop - filterHeight - 55, 340);
            text(content, bold, 11, DARK, MARGIN, noteY, "Cómo leer este informe");
            List<String> explanation = wrap(
                    regular,
                    10,
                    "Cada registro comienza en una página nueva. Los datos se organizan "
                            + "por identidad del ave, medidas, condiciones y notas de campo.",
                    CONTENT_WIDTH
            );
            y = noteY - 22;
            for (String line : explanation) {
                text(content, regular, 10, MUTED, MARGIN, y, line);
                y -= 15;
            }

            text(content, regular, 9, MUTED, MARGIN, 83,
                    "Generado " + RecordReportText.generated(generatedAt));
            text(content, regular, 9, MUTED, MARGIN, 67,
                    "Las fotos no se incrustan; cada ficha indica cuántas están vinculadas.");
        }
    }

    private static void coverCard(
            PDPageContentStream content,
            PDFont regular,
            PDFont bold,
            float x,
            float bottom,
            float width,
            float height,
            String label,
            String value
    ) throws IOException {
        fillRect(content, x, bottom, width, height, PALE);
        strokeRect(content, x, bottom, width, height, BORDER);
        text(content, bold, 8.5f, GREEN, x + 14, bottom + height - 23, label);
        text(content, bold, 17, DARK, x + 14, bottom + 21, value);
    }

    private static void addRecordPages(
            PDDocument document,
            PDFont regular,
            PDFont bold,
            BirdEventReportRow record,
            int recordIndex,
            int totalRecords
    ) throws IOException {
        List<String> notes = wrap(
                regular,
                9.4f,
                RecordReportText.display(record.observations()),
                CONTENT_WIDTH - 28
        );
        int firstPageCapacity = 10;
        int consumed = Math.min(firstPageCapacity, notes.size());
        List<Field> overflowFields = overflowFields(regular, record);
        addMainRecordPage(
                document,
                regular,
                bold,
                record,
                recordIndex,
                totalRecords,
                notes.subList(0, consumed),
                notes.size() > consumed
        );

        if (!overflowFields.isEmpty()) {
            addCompleteFieldPages(
                    document,
                    regular,
                    bold,
                    record,
                    recordIndex,
                    totalRecords,
                    overflowFields
            );
        }

        int offset = consumed;
        int continuationCapacity = 45;
        while (offset < notes.size()) {
            int end = Math.min(notes.size(), offset + continuationCapacity);
            addNotesContinuationPage(
                    document,
                    regular,
                    bold,
                    record,
                    recordIndex,
                    totalRecords,
                    notes.subList(offset, end),
                    end < notes.size()
            );
            offset = end;
        }
    }

    private static void addMainRecordPage(
            PDDocument document,
            PDFont regular,
            PDFont bold,
            BirdEventReportRow record,
            int recordIndex,
            int totalRecords,
            List<String> noteLines,
            boolean continues
    ) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            fillPage(content, PAPER);
            recordHeader(content, regular, bold, record, recordIndex, totalRecords);

            float gap = 13;
            float columnWidth = (CONTENT_WIDTH - gap) / 2;
            List<Field> bird = birdFields(record);
            List<Field> measures = measureFields(record);
            drawFieldCard(
                    content,
                    regular,
                    bold,
                    MARGIN,
                    510,
                    columnWidth,
                    190,
                    "EL AVE",
                    bird
            );
            drawFieldCard(
                    content,
                    regular,
                    bold,
                    MARGIN + columnWidth + gap,
                    510,
                    columnWidth,
                    190,
                    "MEDIDAS",
                    measures
            );

            List<Field> conditions = conditionFields(record);
            List<Field> identity = identityFields(record);
            drawGridCard(
                    content,
                    regular,
                    bold,
                    MARGIN,
                    400,
                    CONTENT_WIDTH,
                    96,
                    "ESPECIE Y PROCEDENCIA",
                    identity
            );
            drawGridCard(
                    content,
                    regular,
                    bold,
                    MARGIN,
                    290,
                    CONTENT_WIDTH,
                    96,
                    "CONDICIONES",
                    conditions
            );

            fillRect(content, MARGIN, 76, CONTENT_WIDTH, 200, Color.WHITE);
            strokeRect(content, MARGIN, 76, CONTENT_WIDTH, 200, BORDER);
            text(content, bold, 9.5f, GREEN, MARGIN + 14, 253, "NOTAS DE CAMPO");
            float y = 229;
            for (String line : noteLines) {
                text(content, regular, 9.4f, DARK, MARGIN + 14, y, line);
                y -= 13.5f;
            }
            if (continues) {
                text(content, regular, 8.5f, GREEN, MARGIN + 14, 92,
                        "Las notas continúan en la página siguiente.");
            }
        }
    }

    private static List<Field> birdFields(BirdEventReportRow record) {
        return List.of(
                field("Sexo", RecordReportText.friendly(record.sexCode())),
                field("Edad EURING", record.ageEuringCode()),
                field("Estado", RecordReportText.friendly(record.status())),
                field("Estado vital", record.dead() ? "Fallecida" : "Viva"),
                field("Condición", RecordReportText.friendly(record.birdCondition())),
                field("Reproducción", RecordReportText.friendly(record.reproductiveStatus())),
                field("Muda", RecordReportText.friendly(record.moultIntensity())),
                field("Extensión de muda", RecordReportText.friendly(record.moultExtension())),
                field("Vuelta", RecordReportText.friendly(record.returnStatus())),
                field("Anillador", record.ringerInitials()),
                field("Clasificación", RecordReportText.friendly(record.captureType()))
        );
    }

    private static List<Field> measureFields(BirdEventReportRow record) {
        return List.of(
                field("Ala", RecordReportText.unit(record.wing(), "mm")),
                field("P3", RecordReportText.unit(record.p3(), "mm")),
                field("Torso", RecordReportText.unit(record.torso(), "mm")),
                field("Peso", RecordReportText.unit(record.weight(), "g")),
                field("Grasa", RecordReportText.display(record.fatScore())),
                field("Músculo", RecordReportText.display(record.muscleScore())),
                field("Fotos vinculadas", Integer.toString(record.photoCount()))
        );
    }

    private static List<Field> identityFields(BirdEventReportRow record) {
        return List.of(
                field("Código especie", record.speciesCode()),
                field("Nombre científico", record.speciesScientificName()),
                field("Nombre común", record.speciesCommonName()),
                field("Archivo de origen", record.sourceName()),
                field("Referencia origen", record.sourceReference())
        );
    }

    private static List<Field> conditionFields(BirdEventReportRow record) {
        return List.of(
                field("Nubes", RecordReportText.friendly(record.clouds())),
                field("Lluvia", RecordReportText.friendly(record.rain())),
                field("Sensación térmica", unitText(record.thermalSensation(), "°C")),
                field("Viento", RecordReportText.friendly(record.wind())),
                field("Coordenadas", RecordReportText.coordinates(
                        record.latitude(),
                        record.longitude()
                )),
                field("Ubicación libre", record.locationText())
        );
    }

    private static List<Field> overflowFields(
            PDFont regular,
            BirdEventReportRow record
    ) throws IOException {
        java.util.LinkedHashMap<String, Field> overflow = new java.util.LinkedHashMap<>();
        addIfOverflow(
                overflow,
                regular,
                18,
                CONTENT_WIDTH,
                field("Especie mostrada", record.species())
        );
        addIfOverflow(
                overflow,
                regular,
                10.5f,
                CONTENT_WIDTH,
                field("Anilla y tipo", "Anilla " + RecordReportText.display(record.ringNumber())
                        + " · " + record.eventType())
        );
        String moment = RecordReportText.date(record.eventDate())
                + " · " + RecordReportText.time(record.eventTime())
                + " · " + RecordReportText.location(record.detail());
        if (wrap(regular, 9.2f, moment, CONTENT_WIDTH).size() > 2) {
            overflow.put("Momento y lugar", field("Momento y lugar", moment));
        }

        float fieldWidth = ((CONTENT_WIDTH - 13) / 2) - 130;
        for (Field field : birdFields(record)) {
            addIfOverflow(overflow, regular, 6.8f, fieldWidth, field);
        }
        for (Field field : measureFields(record)) {
            addIfOverflow(overflow, regular, 6.8f, fieldWidth, field);
        }
        float gridWidth = ((CONTENT_WIDTH - 26) / 2) - 97;
        for (Field field : identityFields(record)) {
            addIfOverflow(overflow, regular, 6.8f, gridWidth, field);
        }
        for (Field field : conditionFields(record)) {
            addIfOverflow(overflow, regular, 6.8f, gridWidth, field);
        }
        return List.copyOf(overflow.values());
    }

    private static void addIfOverflow(
            java.util.LinkedHashMap<String, Field> overflow,
            PDFont font,
            float size,
            float maximumWidth,
            Field field
    ) throws IOException {
        String value = RecordReportText.display(field.value());
        if (value.lines().count() > 1
                || textWidth(font, size, supportedText(font, value)) > maximumWidth) {
            overflow.putIfAbsent(field.label(), field);
        }
    }

    private static void addCompleteFieldPages(
            PDDocument document,
            PDFont regular,
            PDFont bold,
            BirdEventReportRow record,
            int recordIndex,
            int totalRecords,
            List<Field> fields
    ) throws IOException {
        List<FlowLine> lines = new ArrayList<>();
        for (Field field : fields) {
            lines.add(new FlowLine(field.label(), true));
            for (String line : wrap(
                    regular,
                    9.2f,
                    field.value(),
                    CONTENT_WIDTH - 28
            )) {
                lines.add(new FlowLine(line, false));
            }
            lines.add(new FlowLine("", false));
        }

        int capacity = 42;
        for (int offset = 0; offset < lines.size(); offset += capacity) {
            int end = Math.min(lines.size(), offset + capacity);
            addCompleteFieldPage(
                    document,
                    regular,
                    bold,
                    record,
                    recordIndex,
                    totalRecords,
                    lines.subList(offset, end),
                    end < lines.size()
            );
        }
    }

    private static void addCompleteFieldPage(
            PDDocument document,
            PDFont regular,
            PDFont bold,
            BirdEventReportRow record,
            int recordIndex,
            int totalRecords,
            List<FlowLine> lines,
            boolean continues
    ) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            fillPage(content, PAPER);
            topBrand(content, regular, bold, recordIndex, totalRecords);
            fittedText(
                    content,
                    regular,
                    14,
                    DARK,
                    MARGIN,
                    760,
                    RecordReportText.display(record.species()),
                    CONTENT_WIDTH
            );
            text(content, bold, 11, GREEN, MARGIN, 726,
                    "DATOS COMPLETOS · CONTINUACIÓN");
            fillRect(content, MARGIN, 72, CONTENT_WIDTH, 632, Color.WHITE);
            strokeRect(content, MARGIN, 72, CONTENT_WIDTH, 632, BORDER);
            float y = 678;
            for (FlowLine line : lines) {
                text(
                        content,
                        line.label() ? bold : regular,
                        line.label() ? 8.6f : 9.2f,
                        line.label() ? GREEN : DARK,
                        MARGIN + 14,
                        y,
                        line.value()
                );
                y -= 13.5f;
            }
            if (continues) {
                text(content, regular, 8.5f, GREEN, MARGIN + 14, 88,
                        "Los datos continúan en la página siguiente.");
            }
        }
    }

    private static void addNotesContinuationPage(
            PDDocument document,
            PDFont regular,
            PDFont bold,
            BirdEventReportRow record,
            int recordIndex,
            int totalRecords,
            List<String> noteLines,
            boolean continues
    ) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            fillPage(content, PAPER);
            topBrand(content, regular, bold, recordIndex, totalRecords);
            text(content, regular, 18, DARK, MARGIN, 760,
                    RecordReportText.display(record.species()));
            text(content, regular, 10, MUTED, MARGIN, 739,
                    "Anilla " + RecordReportText.display(record.ringNumber()));
            text(content, bold, 11, GREEN, MARGIN, 704,
                    "NOTAS DE CAMPO · CONTINUACIÓN");
            fillRect(content, MARGIN, 72, CONTENT_WIDTH, 610, Color.WHITE);
            strokeRect(content, MARGIN, 72, CONTENT_WIDTH, 610, BORDER);
            float y = 656;
            for (String line : noteLines) {
                text(content, regular, 9.4f, DARK, MARGIN + 14, y, line);
                y -= 12.2f;
            }
            if (continues) {
                text(content, regular, 8.5f, GREEN, MARGIN + 14, 88,
                        "Las notas continúan en la página siguiente.");
            }
        }
    }

    private static void recordHeader(
            PDPageContentStream content,
            PDFont regular,
            PDFont bold,
            BirdEventReportRow record,
            int recordIndex,
            int totalRecords
    ) throws IOException {
        topBrand(content, regular, bold, recordIndex, totalRecords);
        text(content, regular, 18, DARK, MARGIN, 768,
                RecordReportText.display(record.species()));
        text(
                content,
                regular,
                10.5f,
                GREEN,
                MARGIN,
                746,
                "Anilla " + RecordReportText.display(record.ringNumber())
                        + " · " + record.eventType()
        );
        String moment = RecordReportText.date(record.eventDate())
                + " · " + RecordReportText.time(record.eventTime())
                + " · " + RecordReportText.location(record.detail());
        List<String> momentLines = wrap(regular, 9.2f, moment, CONTENT_WIDTH);
        float y = 727;
        for (int index = 0; index < Math.min(2, momentLines.size()); index++) {
            text(content, regular, 9.2f, MUTED, MARGIN, y, momentLines.get(index));
            y -= 13;
        }
        line(content, MARGIN, 711, PAGE_WIDTH - MARGIN, 711, BORDER, 0.8f);
    }

    private static void topBrand(
            PDPageContentStream content,
            PDFont regular,
            PDFont bold,
            int recordIndex,
            int totalRecords
    ) throws IOException {
        fillRect(content, 0, PAGE_HEIGHT - 8, PAGE_WIDTH, 8, GREEN);
        text(content, bold, 9, GREEN, MARGIN, 806, "RINGLOG · INFORME DE REGISTROS");
        rightText(
                content,
                regular,
                8.5f,
                MUTED,
                PAGE_WIDTH - MARGIN,
                806,
                "Registro " + recordIndex + " de " + totalRecords
        );
    }

    private static void drawFieldCard(
            PDPageContentStream content,
            PDFont regular,
            PDFont bold,
            float x,
            float bottom,
            float width,
            float height,
            String title,
            List<Field> fields
    ) throws IOException {
        fillRect(content, x, bottom, width, height, Color.WHITE);
        strokeRect(content, x, bottom, width, height, BORDER);
        text(content, bold, 9.2f, GREEN, x + 13, bottom + height - 22, title);
        float y = bottom + height - 43;
        float valueX = x + 118;
        for (Field field : fields) {
            text(content, regular, 8.2f, MUTED, x + 13, y, field.label());
            fittedText(
                    content,
                    regular,
                    8.7f,
                    DARK,
                    valueX,
                    y,
                    field.value(),
                    x + width - valueX - 12
            );
            y -= 13.5f;
        }
    }

    private static void drawGridCard(
            PDPageContentStream content,
            PDFont regular,
            PDFont bold,
            float x,
            float bottom,
            float width,
            float height,
            String title,
            List<Field> fields
    ) throws IOException {
        fillRect(content, x, bottom, width, height, Color.WHITE);
        strokeRect(content, x, bottom, width, height, BORDER);
        text(content, bold, 9.2f, GREEN, x + 13, bottom + height - 21, title);
        float columnWidth = (width - 26) / 2;
        for (int index = 0; index < fields.size(); index++) {
            int column = index % 2;
            int row = index / 2;
            float fieldX = x + 13 + column * columnWidth;
            float y = bottom + height - 45 - row * 18;
            Field field = fields.get(index);
            text(content, regular, 8.2f, MUTED, fieldX, y, field.label());
            fittedText(
                    content,
                    regular,
                    8.7f,
                    DARK,
                    fieldX + 91,
                    y,
                    field.value(),
                    columnWidth - 97
            );
        }
    }

    private static void appendFooters(
            PDDocument document,
            PDFont regular,
            List<String> labels
    ) throws IOException {
        int totalPages = document.getNumberOfPages();
        for (int index = 0; index < totalPages; index++) {
            PDPage page = document.getPage(index);
            try (PDPageContentStream content = new PDPageContentStream(
                    document,
                    page,
                    PDPageContentStream.AppendMode.APPEND,
                    true,
                    true
            )) {
                line(content, MARGIN, 48, PAGE_WIDTH - MARGIN, 48, BORDER, 0.6f);
                text(content, regular, 8, MUTED, MARGIN, 31, labels.get(index));
                rightText(
                        content,
                        regular,
                        8,
                        MUTED,
                        PAGE_WIDTH - MARGIN,
                        31,
                        "Página " + (index + 1) + " de " + totalPages
                );
            }
        }
    }

    private static void fillPage(PDPageContentStream content, Color color)
            throws IOException {
        fillRect(content, 0, 0, PAGE_WIDTH, PAGE_HEIGHT, color);
    }

    private static void fillRect(
            PDPageContentStream content,
            float x,
            float y,
            float width,
            float height,
            Color color
    ) throws IOException {
        content.setNonStrokingColor(color);
        content.addRect(x, y, width, height);
        content.fill();
    }

    private static void strokeRect(
            PDPageContentStream content,
            float x,
            float y,
            float width,
            float height,
            Color color
    ) throws IOException {
        content.setStrokingColor(color);
        content.setLineWidth(0.7f);
        content.addRect(x, y, width, height);
        content.stroke();
    }

    private static void line(
            PDPageContentStream content,
            float x1,
            float y1,
            float x2,
            float y2,
            Color color,
            float width
    ) throws IOException {
        content.setStrokingColor(color);
        content.setLineWidth(width);
        content.moveTo(x1, y1);
        content.lineTo(x2, y2);
        content.stroke();
    }

    private static void text(
            PDPageContentStream content,
            PDFont font,
            float size,
            Color color,
            float x,
            float y,
            String value
    ) throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.setNonStrokingColor(color);
        content.newLineAtOffset(x, y);
        content.showText(supportedText(font, RecordReportText.display(value)));
        content.endText();
    }

    private static void rightText(
            PDPageContentStream content,
            PDFont font,
            float size,
            Color color,
            float right,
            float y,
            String value
    ) throws IOException {
        String safe = supportedText(font, value);
        float width = textWidth(font, size, safe);
        text(content, font, size, color, right - width, y, safe);
    }

    private static void fittedText(
            PDPageContentStream content,
            PDFont font,
            float size,
            Color color,
            float x,
            float y,
            String value,
            float maximumWidth
    ) throws IOException {
        String safe = supportedText(font, RecordReportText.display(value));
        float adjusted = size;
        while (adjusted > 6.8f && textWidth(font, adjusted, safe) > maximumWidth) {
            adjusted -= 0.3f;
        }
        if (textWidth(font, adjusted, safe) > maximumWidth) {
            safe = ellipsize(font, adjusted, safe, maximumWidth);
        }
        text(content, font, adjusted, color, x, y, safe);
    }

    private static String ellipsize(
            PDFont font,
            float size,
            String value,
            float maximumWidth
    ) throws IOException {
        String suffix = "...";
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String next = result + new String(Character.toChars(codePoint)) + suffix;
            if (textWidth(font, size, next) > maximumWidth) {
                break;
            }
            result.appendCodePoint(codePoint);
            offset += Character.charCount(codePoint);
        }
        return result + suffix;
    }

    private static List<String> wrap(
            PDFont font,
            float size,
            String value,
            float maximumWidth
    ) throws IOException {
        List<String> lines = new ArrayList<>();
        String displayed = RecordReportText.display(value);
        for (String rawParagraph : displayed.split("\\R", -1)) {
            String paragraph = supportedText(font, rawParagraph);
            if (paragraph.isBlank()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.trim().split("\\s+")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (textWidth(font, size, candidate) <= maximumWidth) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                if (textWidth(font, size, word) <= maximumWidth) {
                    line.append(word);
                } else {
                    splitLongWord(font, size, word, maximumWidth, lines, line);
                }
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
            }
        }
        return lines.isEmpty() ? List.of("—") : lines;
    }

    private static void splitLongWord(
            PDFont font,
            float size,
            String word,
            float maximumWidth,
            List<String> lines,
            StringBuilder tail
    ) throws IOException {
        StringBuilder part = new StringBuilder();
        for (int offset = 0; offset < word.length();) {
            int codePoint = word.codePointAt(offset);
            String candidate = part + new String(Character.toChars(codePoint));
            if (!part.isEmpty() && textWidth(font, size, candidate) > maximumWidth) {
                lines.add(part.toString());
                part.setLength(0);
            }
            part.appendCodePoint(codePoint);
            offset += Character.charCount(codePoint);
        }
        tail.append(part);
    }

    private static float textWidth(PDFont font, float size, String value)
            throws IOException {
        return font.getStringWidth(value) / 1000f * size;
    }

    private static String supportedText(PDFont font, String value) throws IOException {
        StringBuilder safe = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint)
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                if (safe.isEmpty() || safe.charAt(safe.length() - 1) != ' ') {
                    safe.append(' ');
                }
                offset += Character.charCount(codePoint);
                continue;
            }
            String character = new String(Character.toChars(codePoint));
            try {
                font.getStringWidth(character);
                safe.append(character);
            } catch (IOException | IllegalArgumentException exception) {
                throw new IOException(
                        "El PDF contiene un carácter que la fuente no puede representar: "
                                + character,
                        exception
                );
            }
            offset += Character.charCount(codePoint);
        }
        return safe.toString();
    }

    private static String dateRange(List<BirdEventReportRow> records) {
        String first = records.stream().map(BirdEventReportRow::eventDate)
                .filter(value -> value != null && !value.isBlank())
                .min(String::compareTo)
                .map(RecordReportText::date)
                .orElse("—");
        String last = records.stream().map(BirdEventReportRow::eventDate)
                .filter(value -> value != null && !value.isBlank())
                .max(String::compareTo)
                .map(RecordReportText::date)
                .orElse("—");
        return first.equals(last) ? first : first + " - " + last;
    }

    private static String unitText(String value, String unit) {
        return value == null || value.isBlank() ? "—" : value + " " + unit;
    }

    private static PDFont loadRegularFont(PDDocument document) throws IOException {
        try (InputStream font = PdfRecordReportWriter.class.getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
        )) {
            if (font == null) {
                return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            }
            return PDType0Font.load(document, font, true);
        }
    }

    private static Field field(String label, Object value) {
        return new Field(label, RecordReportText.display(value));
    }

    private record Field(String label, String value) {
    }

    private record FlowLine(String value, boolean label) {
    }
}
