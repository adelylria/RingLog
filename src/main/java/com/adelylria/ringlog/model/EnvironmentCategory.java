package com.adelylria.ringlog.model;

/** Fixed environmental fields whose selectable values are user-managed. */
public enum EnvironmentCategory {
    CLOUDS("Nubes"),
    RAIN("Lluvia"),
    THERMAL_SENSATION("Sensación térmica"),
    WIND("Viento");

    private final String label;

    EnvironmentCategory(String label) { this.label = label; }

    public String label() { return label; }
    public String code() { return name(); }
    @Override public String toString() { return label; }
}
