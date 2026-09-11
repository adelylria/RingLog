package com.adelylria.ringlog.model;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Official bird-state codes used by the ringing form. */
public final class BirdStatusCatalog {

    private static final List<Entry> ENTRIES = List.of(
            entry("B0", "En buen estado", "Aparentemente en buenas condiciones"),
            entry("B1", "En buen estado", "Retenido durante la noche antes de liberarlo"),

            entry("F0", "Lesiones previas y enfermedades",
                    "Antigua herida curada o en proceso de curación"),
            entry("F1", "Lesiones previas y enfermedades",
                    "Con una malformación (p. ej., pico extremadamente curvado)"),
            entry("F2", "Lesiones previas y enfermedades", "Presencia de garrapatas"),
            entry("F3", "Lesiones previas y enfermedades", "Presencia de otros parásitos"),
            entry("F4", "Lesiones previas y enfermedades", "Sarna u hongos"),
            entry("F5", "Lesiones previas y enfermedades",
                    "Lesión en la pata causada por la anilla"),
            entry("F9", "Lesiones previas y enfermedades", "Otros"),

            entry("E0", "Manipulaciones", "Extracción de sangre"),
            entry("E1", "Manipulaciones", "Extracción de tejidos o biopsias"),
            entry("E2", "Manipulaciones", "Marcas alares especiales o radiotelemetría"),
            entry("E3", "Manipulaciones", "Estudios de alimentación"),
            entry("E9", "Manipulaciones",
                    "Otras manipulaciones que comportan heridas o riesgo"),

            entry("L0", "Lesión leve", "Lengua"),
            entry("L1", "Lesión leve", "Pata (p. ej., herida superficial)"),
            entry("L2", "Lesión leve", "Ojo"),
            entry("L3", "Lesión leve", "Cuerpo"),
            entry("L4", "Lesión leve", "Pérdida de la cola"),
            entry("L5", "Lesión leve", "Ala"),
            entry("L6", "Lesión leve", "Plumaje empapado"),
            entry("L7", "Lesión leve", "Hipotermia"),
            entry("L8", "Lesión leve", "Insolación"),
            entry("L9", "Lesión leve", "Otros"),

            entry("G0", "Lesión grave (no imposibilita el vuelo)",
                    "Lesión interna (p. ej., sangra por la boca)"),
            entry("G1", "Lesión grave (no imposibilita el vuelo)", "Lengua"),
            entry("G2", "Lesión grave (no imposibilita el vuelo)",
                    "Pata (p. ej., rota o dislocada). Rampa en limícolas"),
            entry("G3", "Lesión grave (no imposibilita el vuelo)", "Ojo"),
            entry("G4", "Lesión grave (no imposibilita el vuelo)",
                    "Cuerpo (p. ej., herida profunda)"),
            entry("G9", "Lesión grave (no imposibilita el vuelo)", "Otros"),

            entry("V0", "No puede volar", "Lesión grave en el ala (p. ej., rota o dislocada)"),
            entry("V1", "No puede volar", "Estrés o choque"),
            entry("V2", "No puede volar", "Lesión muy grave en el cuerpo"),
            entry("V3", "No puede volar", "Condición física pésima"),
            entry("V4", "No puede volar", "Hipotermia"),
            entry("V5", "No puede volar", "Insolación"),
            entry("V6", "No puede volar", "Lesión interna (p. ej., sangra por la boca)"),
            entry("V9", "No puede volar", "Otras causas"),

            entry("X0", "Muerto", "Viento (p. ej., ahogado en la red)"),
            entry("X1", "Muerto", "Depredación (gato o perro)"),
            entry("X2", "Muerto", "Depredación (otros)"),
            entry("X3", "Muerto", "Hipotermia"),
            entry("X4", "Muerto", "Insolación"),
            entry("X5", "Muerto", "Manipulación del anillador"),
            entry("X6", "Muerto", "Acción o mal uso de la trampa"),
            entry("X7", "Muerto", "Agua (ahogado)"),
            entry("X8", "Muerto", "Lesión interna"),
            entry("X9", "Muerto", "Otras causas")
    );

    private BirdStatusCatalog() {
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static Optional<Entry> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return ENTRIES.stream().filter(entry -> entry.code().equals(normalized)).findFirst();
    }

    public static String display(String code) {
        if (code == null || code.isBlank()) {
            return "—";
        }
        String preserved = code.trim();
        return find(preserved).map(Entry::displayLabel).orElse(preserved);
    }

    private static Entry entry(String code, String category, String description) {
        return new Entry(code, category, description);
    }

    public record Entry(String code, String category, String description) {

        public Entry {
            code = required(code, "code").toUpperCase(Locale.ROOT);
            category = required(category, "category");
            description = required(description, "description");
        }

        public String displayLabel() {
            return code + " · " + category + " — " + description;
        }

        private static String required(String value, String name) {
            String result = Objects.requireNonNull(value, name).trim();
            if (result.isEmpty()) {
                throw new IllegalArgumentException(name + " cannot be blank");
            }
            return result;
        }
    }
}
