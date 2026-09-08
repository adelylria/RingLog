package com.adelylria.ringlog.diagnostics;

import java.util.regex.Pattern;

/** Removes user content and credentials before arbitrary failures reach persistent logs. */
public final class LogSanitizer {

    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)(token|password|secret|raw_payload|observations?|"
                    + "canonical_snapshot|alternative_snapshot|snapshot)\\s*[:=]\\s*([^;\\r\\n,]+)"
    );
    private static final Pattern LINE_BREAKS = Pattern.compile("[\\r\\n]+");

    private LogSanitizer() {
    }

    public static String failure(Throwable failure) {
        if (failure == null) {
            return "UnknownFailure";
        }
        String type = failure.getClass().getSimpleName();
        String message = sanitize(failure.getMessage());
        return message.isBlank() ? type : type + ": " + message;
    }

    public static String label(String value) {
        String sanitized = sanitize(value);
        return sanitized.length() > 80 ? sanitized.substring(0, 80) : sanitized;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String oneLine = LINE_BREAKS.matcher(value).replaceAll(" ").strip();
        return SENSITIVE_ASSIGNMENT.matcher(oneLine).replaceAll("$1=[REDACTED]");
    }
}
