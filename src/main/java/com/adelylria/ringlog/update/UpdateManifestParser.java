package com.adelylria.ringlog.update;

import java.io.IOException;
import java.net.URI;
import java.util.EnumMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;

/** Strict parser for the signed RingLog Update v1 manifest. */
public final class UpdateManifestParser {

    public static final String FORMAT = "RingLog Update";
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final long MAX_INSTALLER_BYTES = 1024L * 1024L * 1024L;

    private final JsonFactory factory = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_MANIFEST_BYTES)
                    .maxNestingDepth(6)
                    .maxStringLength(8_192)
                    .build())
            .build();

    public UpdateManifest parse(byte[] bytes) throws UpdateException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_MANIFEST_BYTES) {
            throw new UpdateException("El manifest de actualización tiene un tamaño inválido.");
        }
        try (JsonParser parser = factory.createParser(bytes)) {
            requireToken(parser.nextToken(), JsonToken.START_OBJECT);
            String format = null;
            Integer formatVersion = null;
            SemanticVersion version = null;
            Map<UpdateArchitecture, UpdateAsset> assets = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                requireToken(parser.currentToken(), JsonToken.FIELD_NAME);
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "format" -> format = requireText(parser);
                    case "formatVersion" -> formatVersion = requireInteger(parser);
                    case "version" -> version = SemanticVersion.parse(requireText(parser))
                            .orElseThrow(() -> new UpdateException(
                                    "La versión del manifest no es válida."
                            ));
                    case "assets" -> assets = parseAssets(parser);
                    default -> throw new UpdateException(
                            "El manifest contiene un campo desconocido: " + field
                    );
                }
            }
            if (parser.nextToken() != null) {
                throw new UpdateException("El manifest contiene datos adicionales.");
            }
            if (!FORMAT.equals(format) || !Integer.valueOf(FORMAT_VERSION).equals(formatVersion)) {
                throw new UpdateException("El formato de actualización no es compatible.");
            }
            if (version == null || assets == null || assets.isEmpty()) {
                throw new UpdateException("El manifest de actualización está incompleto.");
            }
            return new UpdateManifest(version, assets);
        } catch (UpdateException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw new UpdateException("El manifest de actualización no es válido.", exception);
        }
    }

    private Map<UpdateArchitecture, UpdateAsset> parseAssets(JsonParser parser)
            throws IOException, UpdateException {
        requireToken(parser.currentToken(), JsonToken.START_OBJECT);
        Map<UpdateArchitecture, UpdateAsset> assets = new EnumMap<>(UpdateArchitecture.class);
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            requireToken(parser.currentToken(), JsonToken.FIELD_NAME);
            String key = parser.currentName();
            UpdateArchitecture architecture = UpdateArchitecture.fromManifestKey(key)
                    .orElseThrow(() -> new UpdateException(
                            "El manifest contiene una arquitectura desconocida: " + key
                    ));
            parser.nextToken();
            assets.put(architecture, parseAsset(parser));
        }
        return assets;
    }

    private UpdateAsset parseAsset(JsonParser parser) throws IOException, UpdateException {
        requireToken(parser.currentToken(), JsonToken.START_OBJECT);
        URI url = null;
        String sha256 = null;
        Long size = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            requireToken(parser.currentToken(), JsonToken.FIELD_NAME);
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "url" -> url = URI.create(requireText(parser));
                case "sha256" -> sha256 = requireText(parser);
                case "size" -> size = requireLong(parser);
                default -> throw new UpdateException(
                        "Un instalador contiene un campo desconocido: " + field
                );
            }
        }
        if (url == null || !"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null) {
            throw new UpdateException("La URL del instalador debe usar HTTPS.");
        }
        if (size == null || size > MAX_INSTALLER_BYTES) {
            throw new UpdateException("El tamaño del instalador no es válido.");
        }
        try {
            return new UpdateAsset(url, sha256, size);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new UpdateException("Los datos del instalador no son válidos.", exception);
        }
    }

    private static String requireText(JsonParser parser) throws IOException, UpdateException {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            throw new UpdateException("Se esperaba un texto en el manifest.");
        }
        return parser.getText();
    }

    private static int requireInteger(JsonParser parser) throws IOException, UpdateException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            throw new UpdateException("Se esperaba un entero en el manifest.");
        }
        return parser.getIntValue();
    }

    private static long requireLong(JsonParser parser) throws IOException, UpdateException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            throw new UpdateException("Se esperaba un tamaño entero en el manifest.");
        }
        return parser.getLongValue();
    }

    private static void requireToken(JsonToken actual, JsonToken expected) throws UpdateException {
        if (actual != expected) {
            throw new UpdateException("La estructura del manifest no es válida.");
        }
    }
}
