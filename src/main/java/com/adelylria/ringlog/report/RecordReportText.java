package com.adelylria.ringlog.report;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import com.adelylria.ringlog.model.view.BirdEventDetail;

final class RecordReportText {

    private static final Locale SPANISH = Locale.forLanguageTag("es-ES");
    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("d MMM uuuu", SPANISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter
            .ofPattern("d MMM uuuu · HH:mm", SPANISH);

    private RecordReportText() {
    }

    static String display(Object value) {
        return value == null || value.toString().isBlank()
                ? "—"
                : value.toString();
    }

    static String friendly(Object value) {
        String text = display(value);
        return switch (text.toUpperCase(Locale.ROOT)) {
            case "M" -> "Macho";
            case "F" -> "Hembra";
            case "CAPTURE" -> "Captura";
            case "RECAPTURE" -> "Recaptura";
            case "MIST_NET" -> "Red japonesa";
            case "GOOD" -> "Buen estado";
            case "CORRECT", "OK" -> "Correcto";
            case "REGULAR" -> "Regular";
            case "POOR" -> "Delicado";
            case "PENDING" -> "Pendiente";
            case "REVIEW" -> "Revisar";
            case "NONE" -> "Ninguno";
            case "LOW" -> "Bajo";
            case "MEDIUM" -> "Medio";
            case "HIGH" -> "Alto";
            case "PARTIAL" -> "Parcial";
            case "NO" -> "No";
            case "LIGHT" -> "Ligero";
            case "MODERATE" -> "Moderado";
            case "RETURN" -> "Retorno";
            default -> text;
        };
    }

    static String coded(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        String raw = value.toString();
        String readable = friendly(raw);
        return raw.equals(readable) ? raw : raw + " · " + readable;
    }

    static String date(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) {
            return "—";
        }
        try {
            return DATE.format(LocalDate.parse(isoDate));
        } catch (DateTimeParseException ignored) {
            return isoDate;
        }
    }

    static String time(String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        return value.length() >= 5 ? value.substring(0, 5) : value;
    }

    static String generated(LocalDateTime value) {
        return DATE_TIME.format(value);
    }

    static String location(BirdEventDetail detail) {
        String catalogued = join(detail.place(), detail.locality(), " · ");
        if (!"—".equals(catalogued)) {
            return catalogued;
        }
        return display(detail.locationText());
    }

    static String coordinates(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return "—";
        }
        return String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude);
    }

    static String unit(Object value, String unit) {
        return value == null ? "—" : value + " " + unit;
    }

    static String join(String first, String second, String separator) {
        boolean hasFirst = first != null && !first.isBlank();
        boolean hasSecond = second != null && !second.isBlank();
        if (hasFirst && hasSecond) {
            return first + separator + second;
        }
        if (hasFirst) {
            return first;
        }
        if (hasSecond) {
            return second;
        }
        return "—";
    }
}
