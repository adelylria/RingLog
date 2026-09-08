package com.adelylria.ringlog.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Persists no diary content: only paths, fingerprints, counts and completion metadata. */
public final class MigrationReceiptStore {

    private static final String FILE_NAME = "migration-receipt.json";

    private final Path receiptFile;

    public MigrationReceiptStore(AppPaths paths) {
        receiptFile = paths.migrationDirectory().resolve(FILE_NAME)
                .toAbsolutePath().normalize();
    }

    public Path receiptFile() {
        return receiptFile;
    }

    public void publish(MigrationReceipt receipt) throws IOException {
        Files.createDirectories(receiptFile.getParent());
        Path temporary = Files.createTempFile(receiptFile.getParent(), ".receipt-", ".tmp");
        try {
            Files.writeString(temporary, json(receipt), StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        receiptFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("No se puede publicar el receipt de forma atómica.", unsupported);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public boolean matches(DatabaseCandidate source, DatabaseCandidate destination) {
        if (source == null || destination == null || !Files.isRegularFile(receiptFile)) {
            return false;
        }
        try {
            String json = Files.readString(receiptFile, StandardCharsets.UTF_8);
            return "1".equals(number(json, "receipt_version"))
                    && "COMPLETED".equals(text(json, "result"))
                    && source.path().equals(Path.of(text(json, "source_database"))
                    .toAbsolutePath().normalize())
                    && destination.path().equals(Path.of(text(json, "destination_database"))
                    .toAbsolutePath().normalize())
                    && source.sha256().equals(text(json, "source_fingerprint"))
                    && destination.sha256().equals(text(json, "destination_fingerprint"));
        } catch (IOException | RuntimeException invalidReceipt) {
            return false;
        }
    }

    public void deleteIfExists() throws IOException {
        Files.deleteIfExists(receiptFile);
    }

    private static String json(MigrationReceipt receipt) {
        return """
                {
                  "receipt_version": %d,
                  "completed_at": "%s",
                  "source_database": "%s",
                  "destination_database": "%s",
                  "source_fingerprint": "%s",
                  "destination_fingerprint": "%s",
                  "migrated_media_count": %d,
                  "result": "%s"
                }
                """.formatted(
                receipt.receiptVersion(),
                escape(receipt.completedAt()),
                escape(receipt.sourceDatabase().toString()),
                escape(receipt.destinationDatabase().toString()),
                escape(receipt.sourceFingerprint()),
                escape(receipt.destinationFingerprint()),
                receipt.migratedMediaCount(),
                escape(receipt.result())
        );
    }

    private static String text(String json, String key) {
        Matcher matcher = Pattern.compile(
                "\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\""
        ).matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Falta " + key);
        }
        return unescape(matcher.group(1));
    }

    private static String number(String json, String key) {
        Matcher matcher = Pattern.compile(
                "\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*([0-9]+)"
        ).matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Falta " + key);
        }
        return matcher.group(1);
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (Character.isISOControl(character)) {
                        result.append("\\u%04x".formatted((int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.toString();
    }

    private static String unescape(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '\\') {
                result.append(character);
                continue;
            }
            if (++index >= value.length()) {
                throw new IllegalArgumentException("Escape JSON incompleto");
            }
            char escaped = value.charAt(index);
            switch (escaped) {
                case '\\', '"', '/' -> result.append(escaped);
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> {
                    if (index + 4 >= value.length()) {
                        throw new IllegalArgumentException("Unicode JSON incompleto");
                    }
                    result.append((char) Integer.parseInt(
                            value.substring(index + 1, index + 5), 16
                    ));
                    index += 4;
                }
                default -> throw new IllegalArgumentException("Escape JSON no válido");
            }
        }
        return result.toString();
    }
}
