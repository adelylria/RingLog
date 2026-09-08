package com.adelylria.ringlog.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Downloads, authenticates and evaluates one immutable update manifest pair. */
public final class UpdateChecker {

    private static final int MAX_SIGNATURE_BYTES = 1024;

    private final HttpClient client;
    private final URI manifestUri;
    private final URI signatureUri;
    private final UpdateManifestParser parser;
    private final UpdateSignatureVerifier verifier;
    private final UpdateArchitecture architecture;
    private final SemanticVersion currentVersion;
    private final boolean allowLoopbackHttp;

    public UpdateChecker(
            HttpClient client,
            URI manifestUri,
            URI signatureUri,
            UpdateManifestParser parser,
            UpdateSignatureVerifier verifier,
            UpdateArchitecture architecture,
            SemanticVersion currentVersion,
            boolean allowLoopbackHttp
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.manifestUri = acceptedUri(manifestUri, allowLoopbackHttp);
        this.signatureUri = acceptedUri(signatureUri, allowLoopbackHttp);
        this.parser = Objects.requireNonNull(parser, "parser");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.architecture = Objects.requireNonNull(architecture, "architecture");
        this.currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        this.allowLoopbackHttp = allowLoopbackHttp;
    }

    public CompletableFuture<UpdateCheckResult> check() {
        return fetch(manifestUri, UpdateManifestParser.MAX_MANIFEST_BYTES)
                .thenCompose(manifestBytes -> fetch(signatureUri, MAX_SIGNATURE_BYTES)
                        .thenApply(signatureBytes -> evaluate(manifestBytes, signatureBytes)));
    }

    private UpdateCheckResult evaluate(byte[] manifestBytes, byte[] signatureBytes) {
        try {
            if (!verifier.verify(manifestBytes, signatureBytes)) {
                throw new UpdateException(
                        "El manifest y su firma no pertenecen a la misma publicación."
                );
            }
            UpdateManifest manifest = parser.parse(manifestBytes);
            if (manifest.version().compareTo(currentVersion) <= 0) {
                return UpdateCheckResult.upToDate(manifest);
            }
            return manifest.assetFor(architecture)
                    .map(asset -> UpdateCheckResult.available(manifest, asset))
                    .orElseGet(() -> UpdateCheckResult.unsupported(manifest));
        } catch (UpdateException exception) {
            throw new CompletionException(exception);
        }
    }

    private CompletableFuture<byte[]> fetch(URI uri, int maximumBytes) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/octet-stream, application/json")
                .header("User-Agent", "RingLog-Updater")
                .GET()
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApplyAsync(response -> readResponse(response, maximumBytes));
    }

    private byte[] readResponse(HttpResponse<InputStream> response, int maximumBytes) {
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200) {
                throw new UpdateException(
                        "El servidor de actualizaciones respondió con HTTP "
                                + response.statusCode() + '.'
                );
            }
            acceptedUri(response.uri(), allowLoopbackHttp);
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > maximumBytes) {
                throw new UpdateException("La respuesta de actualización es demasiado grande.");
            }
            byte[] bytes = input.readNBytes(maximumBytes + 1);
            if (bytes.length == 0 || bytes.length > maximumBytes) {
                throw new UpdateException("La respuesta de actualización tiene un tamaño inválido.");
            }
            return bytes;
        } catch (UpdateException exception) {
            throw new CompletionException(exception);
        } catch (IOException exception) {
            throw new CompletionException(new UpdateException(
                    "No se pudo leer la respuesta de actualización.", exception
            ));
        }
    }

    static URI acceptedUri(URI uri, boolean allowLoopbackHttp) {
        Objects.requireNonNull(uri, "uri");
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopback = allowLoopbackHttp
                && "http".equalsIgnoreCase(uri.getScheme())
                && ("127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost()));
        if ((!https && !loopback) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Update endpoints must use HTTPS");
        }
        return uri;
    }
}
