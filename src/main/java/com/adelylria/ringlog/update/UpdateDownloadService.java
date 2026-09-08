package com.adelylria.ringlog.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** Streams a release installer into an isolated temporary directory and verifies it. */
public final class UpdateDownloadService {

    private final HttpClient client;
    private final boolean allowLoopbackHttp;
    private final Path temporaryRoot;

    public UpdateDownloadService(
            HttpClient client,
            boolean allowLoopbackHttp,
            Path temporaryRoot
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.allowLoopbackHttp = allowLoopbackHttp;
        this.temporaryRoot = Objects.requireNonNull(temporaryRoot, "temporaryRoot")
                .toAbsolutePath().normalize();
    }

    public static UpdateDownloadService production(HttpClient client) {
        return new UpdateDownloadService(
                client, false, Path.of(System.getProperty("java.io.tmpdir"))
        );
    }

    public CompletableFuture<Path> download(
            UpdateAsset asset,
            UpdateArchitecture architecture,
            Consumer<DownloadProgress> progress
    ) {
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(architecture, "architecture");
        Consumer<DownloadProgress> safeProgress = progress == null ? ignored -> { } : progress;
        UpdateChecker.acceptedUri(asset.url(), allowLoopbackHttp);
        HttpRequest request = HttpRequest.newBuilder(asset.url())
                .timeout(Duration.ofMinutes(10))
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "RingLog-Updater")
                .GET()
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApplyAsync(response -> store(response, asset, architecture, safeProgress));
    }

    private Path store(
            HttpResponse<InputStream> response,
            UpdateAsset asset,
            UpdateArchitecture architecture,
            Consumer<DownloadProgress> progress
    ) {
        Path directory = null;
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200) {
                throw new UpdateException(
                        "El instalador respondió con HTTP " + response.statusCode() + '.'
                );
            }
            UpdateChecker.acceptedUri(response.uri(), allowLoopbackHttp);
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared >= 0 && declared != asset.size()) {
                throw new UpdateException("El tamaño anunciado del instalador no coincide.");
            }

            Files.createDirectories(temporaryRoot);
            directory = Files.createTempDirectory(temporaryRoot, "ringlog-update-")
                    .toAbsolutePath().normalize();
            String name = "RingLog-Setup-" + architecture.installerSuffix() + ".exe";
            Path partial = directory.resolve(name + ".part");
            Path completed = directory.resolve(name);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (var output = Files.newOutputStream(
                    partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
            )) {
                byte[] buffer = new byte[16_384];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > asset.size()) {
                        throw new UpdateException("El instalador descargado supera el tamaño esperado.");
                    }
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    progress.accept(new DownloadProgress(total, asset.size()));
                }
            }
            if (total != asset.size()) {
                throw new UpdateException("La descarga del instalador está incompleta.");
            }
            String actualHash = HexFormat.of().formatHex(digest.digest());
            if (!MessageDigest.isEqual(
                    actualHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    asset.sha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII)
            )) {
                throw new UpdateException("El SHA-256 del instalador no coincide.");
            }
            try {
                Files.move(partial, completed, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(partial, completed);
            }
            return completed;
        } catch (UpdateException exception) {
            cleanup(directory, exception);
            throw new CompletionException(exception);
        } catch (IOException | NoSuchAlgorithmException | RuntimeException exception) {
            UpdateException update = new UpdateException(
                    "No se pudo preparar el instalador de actualización.", exception
            );
            cleanup(directory, update);
            throw new CompletionException(update);
        }
    }

    private static void cleanup(Path directory, Exception original) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }
}
