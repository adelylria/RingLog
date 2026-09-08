package com.adelylria.ringlog.model;

import java.util.Locale;

public enum EventType {
    RINGING("Anillamiento"),
    CONTROL("Control"),
    RECOVERY("Recuperación");

    private final String displayName;

    EventType(String displayName) {
        this.displayName = displayName;
    }

    public String databaseValue() {
        return name();
    }

    public static EventType fromDatabase(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("El tipo de evento está vacío");
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return displayName;
    }
}
