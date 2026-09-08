package com.adelylria.ringlog.storage;

public enum PhotoArea {
    EVENTS("events"),
    NATIVE("native");

    private final String referencePrefix;

    PhotoArea(String referencePrefix) {
        this.referencePrefix = referencePrefix;
    }

    String referencePrefix() {
        return referencePrefix;
    }
}
