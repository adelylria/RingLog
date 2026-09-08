package com.adelylria.ringlog.model.view;

public record SpeciesOption(long id, String name) {

    @Override
    public String toString() {
        return name;
    }
}
