package com.adelylria.ringlog.importexport.service;

/** A deliberate field-level conflict decision made by the user. */
public record ResolutionRequest(Type type, String value, String notes) {

    public ResolutionRequest {
        if (type == null) {
            throw new IllegalArgumentException("Indica cómo resolver el conflicto.");
        }
        value = optional(value);
        notes = optional(notes);
        if (type == Type.MANUAL_VALUE && value == null) {
            throw new IllegalArgumentException("Indica el valor manual validado.");
        }
        if (type != Type.MANUAL_VALUE && value != null) {
            throw new IllegalArgumentException("Solo la resolución manual acepta un valor propio.");
        }
    }

    public static ResolutionRequest canonical(String notes) {
        return new ResolutionRequest(Type.CANONICAL, null, notes);
    }

    public static ResolutionRequest alternative(String notes) {
        return new ResolutionRequest(Type.ALTERNATIVE, null, notes);
    }

    public static ResolutionRequest manual(String value, String notes) {
        return new ResolutionRequest(Type.MANUAL_VALUE, value, notes);
    }

    public static ResolutionRequest pending(String notes) {
        return new ResolutionRequest(Type.PENDING, null, notes);
    }

    private static String optional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public enum Type {
        CANONICAL,
        ALTERNATIVE,
        MANUAL_VALUE,
        PENDING
    }
}
