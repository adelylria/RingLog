package com.adelylria.ringlog.storage;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/** Converts managed relative media references to physical paths and back. */
public final class MediaPathResolver {

    private final DataPaths paths;

    public MediaPathResolver(DataPaths paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    public Path resolveEventPhoto(String reference) {
        String canonical = validatedReference(reference);
        int separator = canonical.indexOf('/');
        if (separator < 1 || separator == canonical.length() - 1) {
            throw invalidReference();
        }
        String prefix = canonical.substring(0, separator);
        String child = canonical.substring(separator + 1);
        Path root = switch (prefix) {
            case "events" -> paths.eventPhotosDirectory();
            case "native" -> paths.nativePhotosDirectory();
            default -> throw invalidReference();
        };
        return resolveBelow(root, child);
    }

    public Path resolveUnassignedPhoto(String reference) {
        return resolveBelow(paths.unassignedPhotosDirectory(), validatedReference(reference));
    }

    public String toEventReference(PhotoArea area, Path managedFile) {
        Objects.requireNonNull(area, "area");
        Path root = area == PhotoArea.EVENTS
                ? paths.eventPhotosDirectory() : paths.nativePhotosDirectory();
        return area.referencePrefix() + '/' + relativeReference(root, managedFile);
    }

    public String toUnassignedReference(Path managedFile) {
        return relativeReference(paths.unassignedPhotosDirectory(), managedFile);
    }

    private static Path resolveBelow(Path root, String child) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path relative;
        try {
            relative = Path.of(child);
        } catch (InvalidPathException exception) {
            throw invalidReference(exception);
        }
        if (relative.isAbsolute()) {
            throw invalidReference();
        }
        Path resolved = normalizedRoot.resolve(relative).normalize();
        if (!resolved.startsWith(normalizedRoot) || resolved.equals(normalizedRoot)) {
            throw invalidReference();
        }
        return resolved;
    }

    private static String relativeReference(Path root, Path managedFile) {
        Objects.requireNonNull(managedFile, "managedFile");
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedFile = managedFile.toAbsolutePath().normalize();
        if (!normalizedFile.startsWith(normalizedRoot) || normalizedFile.equals(normalizedRoot)) {
            throw invalidReference();
        }
        String reference = normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');
        return validatedReference(reference);
    }

    private static String validatedReference(String reference) {
        if (reference == null || reference.isBlank()) {
            throw invalidReference();
        }
        String value = reference.strip();
        if (value.indexOf('\\') >= 0 || value.startsWith("/")
                || value.matches("(?i)^[a-z]:.*") || value.indexOf('\0') >= 0) {
            throw invalidReference();
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                    || segment.chars().anyMatch(Character::isISOControl)) {
                throw invalidReference();
            }
        }
        try {
            if (Path.of(value).isAbsolute()) {
                throw invalidReference();
            }
        } catch (InvalidPathException exception) {
            throw invalidReference(exception);
        }
        return value;
    }

    private static IllegalArgumentException invalidReference() {
        return new IllegalArgumentException("La referencia de fotografía gestionada no es segura.");
    }

    private static IllegalArgumentException invalidReference(Exception cause) {
        return new IllegalArgumentException(
                "La referencia de fotografía gestionada no es segura.", cause
        );
    }
}
