package com.adelylria.ringlog.application;

import java.util.Locale;

/** Explicit startup mode; portable mode is never inferred from the working directory. */
public enum ApplicationMode {
    NORMAL("normal"),
    PORTABLE_READ_ONLY("portable-readonly");

    public static final String PROPERTY = "ringlog.mode";

    private final String propertyValue;

    ApplicationMode(String propertyValue) {
        this.propertyValue = propertyValue;
    }

    public String propertyValue() {
        return propertyValue;
    }

    public static ApplicationMode detect() {
        String value = System.getProperty(PROPERTY, NORMAL.propertyValue)
                .strip().toLowerCase(Locale.ROOT);
        for (ApplicationMode mode : values()) {
            if (mode.propertyValue.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Modo de inicio de RingLog no reconocido: " + value);
    }
}
