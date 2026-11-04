package com.adelylria.ringlog.model.view;

public record CaptureMethodOption(long id, String name, boolean active) {

    @Override
    public String toString() {
        return name;
    }
}
