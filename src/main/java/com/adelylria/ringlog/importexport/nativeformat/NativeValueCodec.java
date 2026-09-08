package com.adelylria.ringlog.importexport.nativeformat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reversible cell encoding that distinguishes null/empty and avoids Excel limits. */
final class NativeValueCodec {

    static final int INLINE_UTF8_LIMIT = 20_000;
    static final int CHUNK_CHARACTERS = 30_000;

    private NativeValueCodec() {
    }

    static String encode(String value, TextStore store) {
        if (value == null) {
            return "@N";
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        String base64 = Base64.getEncoder().encodeToString(bytes);
        if (bytes.length <= INLINE_UTF8_LIMIT) {
            return "@V:" + base64;
        }
        String key = UUID.randomUUID().toString();
        store.put(key, base64);
        return "@T:" + key;
    }

    static String decode(String encoded, Map<String, String> textStore) {
        if ("@N".equals(encoded)) {
            return null;
        }
        String base64;
        if (encoded != null && encoded.startsWith("@V:")) {
            base64 = encoded.substring(3);
        } else if (encoded != null && encoded.startsWith("@T:")) {
            String key = encoded.substring(3);
            base64 = textStore.get(key);
            if (base64 == null) {
                throw new IllegalArgumentException("Falta el texto interno " + key + ".");
            }
        } else {
            throw new IllegalArgumentException("Valor nativo sin codificación válida.");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            String value = new String(bytes, StandardCharsets.UTF_8);
            if (!Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))
                    .equals(base64)) {
                throw new IllegalArgumentException("El texto interno no es UTF-8 canónico.");
            }
            return value;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("El texto interno está dañado.", invalid);
        }
    }

    static final class TextStore {
        private final Map<String, List<String>> chunks = new LinkedHashMap<>();

        void put(String key, String base64) {
            List<String> values = new ArrayList<>();
            for (int start = 0; start < base64.length(); start += CHUNK_CHARACTERS) {
                values.add(base64.substring(start, Math.min(
                        base64.length(), start + CHUNK_CHARACTERS
                )));
            }
            chunks.put(key, List.copyOf(values));
        }

        Map<String, List<String>> chunks() {
            return chunks;
        }
    }
}
