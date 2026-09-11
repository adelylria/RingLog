package com.adelylria.ringlog.ui.components;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import javax.imageio.ImageIO;

import com.adelylria.ringlog.diagnostics.BuildInfo;

/** Loads individual OpenStreetMap tiles and keeps a policy-friendly local cache. */
final class OpenStreetMapTileStore {

    static final Duration CACHE_LIFETIME = Duration.ofDays(7);
    private static final int MAX_TILE_BYTES = 2 * 1024 * 1024;
    private static final String TILE_SERVER = "https://tile.openstreetmap.org";
    private static final String PROJECT_URL = "https://github.com/adelylria/RingLog";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @FunctionalInterface
    interface Downloader {
        byte[] download(URI uri) throws IOException, InterruptedException;
    }

    private final Path cacheRoot;
    private final Downloader downloader;
    private final Clock clock;

    OpenStreetMapTileStore(Path cacheRoot) {
        this(cacheRoot, httpDownloader(), Clock.systemUTC());
    }

    OpenStreetMapTileStore(Path cacheRoot, Downloader downloader, Clock clock) {
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot")
                .toAbsolutePath().normalize();
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    BufferedImage load(int zoom, int x, int y) throws IOException, InterruptedException {
        validateCoordinates(zoom, x, y);
        Path cached = cachePath(zoom, x, y);
        BufferedImage stale = readImage(cached);
        if (stale != null && isFresh(cached)) {
            return stale;
        }

        try {
            byte[] bytes = downloader.download(tileUri(zoom, x, y));
            BufferedImage downloaded = decode(bytes);
            cacheBestEffort(cached, bytes);
            return downloaded;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (stale != null) {
                return stale;
            }
            throw exception;
        } catch (IOException exception) {
            if (stale != null) {
                return stale;
            }
            throw exception;
        }
    }

    Path cachePath(int zoom, int x, int y) {
        Path result = cacheRoot
                .resolve(Integer.toString(zoom))
                .resolve(Integer.toString(x))
                .resolve(y + ".png")
                .normalize();
        if (!result.startsWith(cacheRoot)) {
            throw new IllegalArgumentException("Referencia de tesela no válida.");
        }
        return result;
    }

    private boolean isFresh(Path cached) {
        try {
            FileTime modified = Files.getLastModifiedTime(cached);
            return modified.toInstant().plus(CACHE_LIFETIME).isAfter(clock.instant());
        } catch (IOException ignored) {
            return false;
        }
    }

    private static BufferedImage readImage(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return ImageIO.read(path.toFile());
        } catch (IOException ignored) {
            return null;
        }
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_TILE_BYTES) {
            throw new IOException("La respuesta cartográfica no es una tesela válida.");
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IOException("La respuesta cartográfica no contiene una imagen.");
        }
        return image;
    }

    private static void cacheBestEffort(Path target, byte[] bytes) {
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), "tile-", ".tmp");
            Files.write(temporary, bytes);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // A read-only or full cache directory must never make the map unusable.
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best-effort cleanup of a non-sensitive cache file.
                }
            }
        }
    }

    private static URI tileUri(int zoom, int x, int y) {
        return URI.create(TILE_SERVER + "/" + zoom + "/" + x + "/" + y + ".png");
    }

    private static void validateCoordinates(int zoom, int x, int y) {
        if (zoom < 0 || zoom > 19) {
            throw new IllegalArgumentException("Nivel de zoom no válido.");
        }
        int limit = 1 << zoom;
        if (x < 0 || x >= limit || y < 0 || y >= limit) {
            throw new IllegalArgumentException("Coordenadas de tesela no válidas.");
        }
    }

    private static Downloader httpDownloader() {
        return uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", userAgent())
                    .header("Accept", "image/png")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HTTP_CLIENT.send(
                    request,
                    HttpResponse.BodyHandlers.ofByteArray()
            );
            if (response.statusCode() != 200) {
                throw new IOException("El servidor cartográfico no está disponible.");
            }
            return response.body();
        };
    }

    private static String userAgent() {
        return "RingLog/" + BuildInfo.applicationVersion() + " (+" + PROJECT_URL + ")";
    }
}
