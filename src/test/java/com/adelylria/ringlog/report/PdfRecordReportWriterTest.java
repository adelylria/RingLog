package com.adelylria.ringlog.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

public final class PdfRecordReportWriterTest {

    private PdfRecordReportWriterTest() {
    }

    public static void cachedMetricsPreservePdfBoxWidthsAndLineBreaks() throws Exception {
        try (PDDocument document = new PDDocument()) {
            for (PDFont font : fonts(document)) {
                try (FontHarness harness = new FontHarness(document, font)) {
                    List<String> values = List.of(
                            "Ánade azulón · Pollença — 31.7 mm",
                            "  Carbonero\tcomún  junto al carrizal.\n\nSegunda línea.\r\nFin.",
                            "Referencia_áñü".repeat(65),
                            "Peso 65.1 g / Grasa 0 / Músculo 2 ".repeat(50),
                            "\t\t   \n\n",
                            ""
                    );
                    for (String value : values) {
                        String safe = normalizeControls(value);
                        require(safe.equals(harness.call("supportedText", value)),
                                "Control normalization must preserve every ordinary Unicode character");
                        require(Float.floatToIntBits(font.getStringWidth(safe))
                                        == Float.floatToIntBits(harness.width(safe)),
                                "Cached advances must preserve PDFBox's exact float accumulation");
                        for (float size : new float[] {6.6f, 6.8f, 8.7f, 9.2f, 9.4f, 10, 18}) {
                            float boundary = font.getStringWidth("Ánade azulón ·") / 1000f * size;
                            for (float width : new float[] {
                                    1, 80, 129, 483, Math.nextDown(boundary), boundary,
                                    Math.nextUp(boundary)
                            }) {
                                List<String> expected = referenceWrap(font, size, value, width);
                                require(expected.equals(harness.call("wrap", size, value, width)),
                                        "Line breaks must match PDFBox at and around fit boundaries");
                            }
                        }
                    }
                }
            }
        }
    }

    public static void fittingPreservesFontSizeAndEllipsis() throws Exception {
        try (PDDocument document = new PDDocument()) {
            for (PDFont font : fonts(document)) {
                try (FontHarness harness = new FontHarness(document, font)) {
                    String value = "Referencia áñü — " + "abcdefghi".repeat(20);
                    for (float width : new float[] {1, 19, 82, 129, 480, 1300}) {
                        float expectedSize = 8.7f;
                        while (expectedSize > 6.8f && textWidth(font, expectedSize, value) > width) {
                            expectedSize -= 0.3f;
                        }
                        String expected = textWidth(font, expectedSize, value) > width
                                ? referenceEllipsize(font, expectedSize, value, width) : value;
                        require(expected.equals(harness.call("ellipsize", expectedSize, value, width))
                                        || textWidth(font, expectedSize, value) <= width,
                                "Ellipsis must keep exactly the same code points at each width");
                        PDPage page = new PDPage();
                        document.addPage(page);
                        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                            harness.call("fittedText", content, 8.7f, java.awt.Color.BLACK,
                                    42f, 720f, value, width);
                        }
                        List<Object> tokens = new PDFStreamParser(page).parse();
                        float actualSize = Float.NaN;
                        for (int index = 1; index < tokens.size(); index++) {
                            if (tokens.get(index) instanceof Operator operator
                                    && "Tf".equals(operator.getName())) {
                                actualSize = ((COSNumber) tokens.get(index - 1)).floatValue();
                            }
                        }
                        require(Math.abs(actualSize - expectedSize) < 0.0001f,
                                "Fitting must preserve the existing 0.3-point font-size steps");
                        PDPage expectedPage = new PDPage();
                        document.addPage(expectedPage);
                        try (PDPageContentStream content = new PDPageContentStream(document, expectedPage)) {
                            content.beginText();
                            content.setFont(font, expectedSize);
                            content.setNonStrokingColor(java.awt.Color.BLACK);
                            content.newLineAtOffset(42, 720);
                            content.showText(expected);
                            content.endText();
                        }
                        try (InputStream actual = page.getContents();
                                InputStream reference = expectedPage.getContents()) {
                            require(Arrays.equals(actual.readAllBytes(), reference.readAllBytes()),
                                    "Fitted PDF operators must retain the same font size and text");
                        }
                    }
                }
            }
        }
    }

    public static void unsupportedCharactersStillFailInsteadOfDisappearing() throws Exception {
        try (PDDocument document = new PDDocument();
                FontHarness harness = new FontHarness(document,
                        new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD))) {
            try {
                harness.call("supportedText", "Texto 中");
                throw new AssertionError("An unsupported character must never be silently removed");
            } catch (IOException expected) {
                require(expected.getMessage().contains("中"),
                        "The original unsupported character must remain in the error message");
            }
        }
    }

    public static void cachedTextPreservesShapingOperatorsAndRenderedPages() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument()) {
            PDFont font = fonts(document).get(0);
            try (FontHarness harness = new FontHarness(document, font)) {
                for (boolean cached : new boolean[] {false, true}) {
                    PDPage page = new PDPage();
                    document.addPage(page);
                    try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                        float y = 720;
                        for (int repetition = 0; repetition < 3; repetition++) {
                            for (String value : List.of(
                                    "Oficina affine fi fl ffi ffl — Ánade azulón",
                                    "A\u0301nade · Ωμέγα · Pollença · ﬁnal · 31.7 mm"
                            )) {
                                for (float size : new float[] {6.8f, 8.7f, 14f}) {
                                    java.awt.Color color = repetition % 2 == 0
                                            ? new java.awt.Color(55, 116, 80) : java.awt.Color.BLACK;
                                    if (cached) {
                                        harness.call("text", content, size, color, 42f, y, value);
                                    } else {
                                        content.beginText();
                                        content.setFont(font, size);
                                        content.setNonStrokingColor(color);
                                        content.newLineAtOffset(42, y);
                                        content.showText(value);
                                        content.endText();
                                    }
                                    y -= 27;
                                }
                            }
                        }
                    }
                }
                try (InputStream original = document.getPage(0).getContents();
                        InputStream cached = document.getPage(1).getContents()) {
                    require(Arrays.equals(original.readAllBytes(), cached.readAllBytes()),
                            "Cached Tj operations must match PDFBox byte-for-byte, including font state");
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                document.save(output);
                pdf = output.toByteArray();
            }
        }
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String expectedText = stripper.getText(document);
            stripper.setStartPage(2);
            stripper.setEndPage(2);
            require(expectedText.equals(stripper.getText(document)),
                    "Subset fonts must retain all repeated Unicode and ligature text after saving");
            PDFRenderer renderer = new PDFRenderer(document);
            var original = renderer.renderImageWithDPI(0, 96);
            var cached = renderer.renderImageWithDPI(1, 96);
            require(original.getWidth() == cached.getWidth()
                            && original.getHeight() == cached.getHeight()
                            && Arrays.equals(original.getRGB(0, 0, original.getWidth(), original.getHeight(),
                                            null, 0, original.getWidth()),
                                    cached.getRGB(0, 0, cached.getWidth(), cached.getHeight(),
                                            null, 0, cached.getWidth())),
                    "Cached shaping must preserve every rendered pixel at different sizes and colors");
        }
    }

    public static void validationReadsContentOnTheLastPage() throws Exception {
        Path file = Files.createTempFile("ringlog-pdf-corrupt-last-page-", ".pdf");
        try {
            try (PDDocument document = new PDDocument()) {
                PDPage cover = new PDPage();
                document.addPage(cover);
                try (PDPageContentStream content = new PDPageContentStream(document, cover)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(42, 720);
                    content.showText("RingLog - Informe de registros");
                    content.endText();
                }
                PDPage corruptLastPage = new PDPage();
                document.addPage(corruptLastPage);
                PDStream brokenContent = new PDStream(document);
                try (var output = brokenContent.createOutputStream(COSName.FLATE_DECODE)) {
                    // A truncated hex operand is invalid PDF content. Invalid zlib
                    // bytes alone are insufficient: PDFBox can recover by skipping them.
                    output.write("<52494E47".getBytes(StandardCharsets.US_ASCII));
                }
                corruptLastPage.setContents(brokenContent);
                document.save(file.toFile());
            }
            try (PDDocument document = Loader.loadPDF(file.toFile())) {
                PDFTextStripper coverOnly = new PDFTextStripper();
                coverOnly.setStartPage(1);
                coverOnly.setEndPage(1);
                require(coverOnly.getText(document).contains("RingLog - Informe de registros"),
                        "The corruption fixture must have a readable, valid cover");
            }
            try {
                PdfRecordReportWriter.validate(file, 1);
                throw new AssertionError("Validation must inspect the last page, beyond a valid cover");
            } catch (IOException | RecordReportException expected) {
                // A readable cover must not hide corrupt content in a later record.
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static List<PDFont> fonts(PDDocument document) throws Exception {
        Method load = PdfRecordReportWriter.class.getDeclaredMethod("loadRegularFont", PDDocument.class);
        load.setAccessible(true);
        return List.of((PDFont) load.invoke(null, document),
                new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD));
    }

    private static String normalizeControls(String value) {
        StringBuilder safe = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint) || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                if (safe.isEmpty() || safe.charAt(safe.length() - 1) != ' ') {
                    safe.append(' ');
                }
            } else {
                safe.appendCodePoint(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        return safe.toString();
    }

    // This deliberately uses PDFBox's original whole-string measurements as the
    // reference, rather than the production cache or incremental measurements.
    private static List<String> referenceWrap(PDFont font, float size, String value, float width)
            throws IOException {
        List<String> lines = new ArrayList<>();
        for (String raw : RecordReportText.display(value).split("\\R", -1)) {
            String paragraph = normalizeControls(raw);
            if (paragraph.isBlank()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.trim().split("\\s+")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (textWidth(font, size, candidate) <= width) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                if (textWidth(font, size, word) <= width) {
                    line.append(word);
                } else {
                    StringBuilder part = new StringBuilder();
                    for (int offset = 0; offset < word.length();) {
                        int codePoint = word.codePointAt(offset);
                        String next = part + new String(Character.toChars(codePoint));
                        if (!part.isEmpty() && textWidth(font, size, next) > width) {
                            lines.add(part.toString());
                            part.setLength(0);
                        }
                        part.appendCodePoint(codePoint);
                        offset += Character.charCount(codePoint);
                    }
                    line.append(part);
                }
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
            }
        }
        return lines.isEmpty() ? List.of("—") : lines;
    }

    private static String referenceEllipsize(PDFont font, float size, String value, float width)
            throws IOException {
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String next = result + new String(Character.toChars(codePoint)) + "...";
            if (textWidth(font, size, next) > width) {
                break;
            }
            result.appendCodePoint(codePoint);
            offset += Character.charCount(codePoint);
        }
        return result + "...";
    }

    private static float textWidth(PDFont font, float size, String value) throws IOException {
        return font.getStringWidth(value) / 1000f * size;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FontHarness implements AutoCloseable {

        private final Object metrics;

        private FontHarness(PDDocument document, PDFont font) throws Exception {
            Class<?> metricsType = Class.forName(PdfRecordReportWriter.class.getName() + "$FontMetrics");
            Constructor<?> constructor = metricsType.getDeclaredConstructor(PDDocument.class, PDFont.class);
            constructor.setAccessible(true);
            metrics = constructor.newInstance(document, font);
        }

        private float width(String value) throws Exception {
            Method method = metrics.getClass().getDeclaredMethod("widthUnits", String.class);
            method.setAccessible(true);
            return (float) method.invoke(metrics, value);
        }

        private Object call(String name, Object... arguments) throws Exception {
            for (Method method : PdfRecordReportWriter.class.getDeclaredMethods()) {
                if (method.getName().equals(name)) {
                    method.setAccessible(true);
                    Object[] allArguments = new Object[arguments.length + 1];
                    if ("fittedText".equals(name) || "text".equals(name)) {
                        allArguments[0] = arguments[0];
                        allArguments[1] = metrics;
                        System.arraycopy(arguments, 1, allArguments, 2, arguments.length - 1);
                    } else {
                        allArguments[0] = metrics;
                        System.arraycopy(arguments, 0, allArguments, 1, arguments.length);
                    }
                    try {
                        return method.invoke(null, allArguments);
                    } catch (InvocationTargetException exception) {
                        if (exception.getCause() instanceof Exception cause) {
                            throw cause;
                        }
                        throw exception;
                    }
                }
            }
            throw new AssertionError("Missing PDF layout method: " + name);
        }

        @Override
        public void close() throws Exception {
            Method method = metrics.getClass().getDeclaredMethod("close");
            method.setAccessible(true);
            method.invoke(metrics);
        }
    }

    public static void main(String[] args) throws Exception {
        cachedMetricsPreservePdfBoxWidthsAndLineBreaks();
        fittingPreservesFontSizeAndEllipsis();
        unsupportedCharactersStillFailInsteadOfDisappearing();
        cachedTextPreservesShapingOperatorsAndRenderedPages();
        validationReadsContentOnTheLastPage();
        System.out.println("PdfRecordReportWriterTest: PASS");
    }
}
