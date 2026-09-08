package com.adelylria.ringlog.report;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import com.adelylria.ringlog.model.view.BirdEventReportRow;

public final class RecordReportService {

    private final Clock clock;
    private final Consumer<Path> beforePublish;

    public RecordReportService() {
        this(Clock.systemDefaultZone(), ignored -> { });
    }

    public RecordReportService(Clock clock) {
        this(clock, ignored -> { });
    }

    RecordReportService(Clock clock, Consumer<Path> beforePublish) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.beforePublish = Objects.requireNonNull(beforePublish, "beforePublish");
    }

    public RecordReportResult export(
            RecordReportFormat format,
            Path destination,
            List<BirdEventReportRow> records,
            List<String> activeFilters
    ) throws RecordReportException {
        validate(format, destination, records);
        List<BirdEventReportRow> snapshot = List.copyOf(records);
        List<String> filters = activeFilters == null
                ? List.of()
                : activeFilters.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .toList();
        Path output = format.pathFor(destination.toAbsolutePath().normalize());
        Path parent = output.getParent();
        Path temporary = null;
        try {
            if (Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
                throw new RecordReportException(
                        "Selecciona un nombre de archivo, no una carpeta."
                );
            }
            DestinationState initialDestination = DestinationState.read(output);
            Files.createDirectories(parent);
            temporary = Files.createTempFile(
                    parent,
                    "." + output.getFileName() + ".",
                    ".tmp"
            );
            LocalDateTime generatedAt = LocalDateTime.now(clock);
            switch (format) {
                case EXCEL -> new ExcelRecordReportWriter().write(
                        temporary,
                        snapshot,
                        filters,
                        generatedAt
                );
                case PDF -> new PdfRecordReportWriter().write(
                        temporary,
                        snapshot,
                        filters,
                        generatedAt
                );
            }
            validateOutput(format, temporary, snapshot.size());
            beforePublish.accept(output);
            DestinationState currentDestination = DestinationState.read(output);
            if (!initialDestination.equals(currentDestination)) {
                throw new RecordReportException(
                        "El archivo de destino ha cambiado durante la exportación. "
                                + "No se ha sustituido."
                );
            }
            replaceAtomically(temporary, output, initialDestination.exists());
            temporary = null;
            return new RecordReportResult(output, snapshot.size(), format);
        } catch (RecordReportException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new RecordReportException(
                    exception.getMessage() == null || exception.getMessage().isBlank()
                            ? "No se pudo crear el informe."
                            : "No se pudo crear el informe: " + exception.getMessage(),
                    exception
            );
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Keep the original error, which is more useful to the user.
                }
            }
        }
    }

    private static void validate(
            RecordReportFormat format,
            Path destination,
            List<BirdEventReportRow> records
    ) throws RecordReportException {
        if (format == null) {
            throw new RecordReportException("Selecciona el formato del informe.");
        }
        if (destination == null || destination.getFileName() == null) {
            throw new RecordReportException("Selecciona dónde guardar el informe.");
        }
        if (records == null || records.isEmpty()) {
            throw new RecordReportException(
                    "No hay registros con los filtros actuales para exportar."
            );
        }
        if (records.size() > format.maximumRecords()) {
            throw new RecordReportException(
                    "El formato " + format + " admite un máximo seguro de "
                            + format.maximumRecords() + " registros."
            );
        }
        if (records.stream().anyMatch(Objects::isNull)) {
            throw new RecordReportException(
                    "Hay un registro que ya no está disponible. Actualiza el diario."
            );
        }
    }

    private static void validateOutput(
            RecordReportFormat format,
            Path output,
            int expectedRecords
    ) throws IOException, RecordReportException {
        switch (format) {
            case EXCEL -> ExcelRecordReportWriter.validate(output, expectedRecords);
            case PDF -> PdfRecordReportWriter.validate(output, expectedRecords);
        }
    }

    private static void replaceAtomically(
            Path source,
            Path destination,
            boolean replaceExisting
    )
            throws IOException {
        try {
            if (replaceExisting) {
                Files.move(
                        source,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } else {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException(
                    "La ubicación elegida no permite sustituir el informe de forma segura.",
                    exception
            );
        }
    }

    private record DestinationState(
            boolean exists,
            boolean regularFile,
            long size,
            FileTime modified,
            Object fileKey
    ) {

        private static DestinationState read(Path path) throws IOException {
            try {
                BasicFileAttributes attributes = Files.readAttributes(
                        path,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                return new DestinationState(
                        true,
                        attributes.isRegularFile(),
                        attributes.size(),
                        attributes.lastModifiedTime(),
                        attributes.fileKey()
                );
            } catch (NoSuchFileException exception) {
                return new DestinationState(false, false, 0, null, null);
            }
        }
    }
}
