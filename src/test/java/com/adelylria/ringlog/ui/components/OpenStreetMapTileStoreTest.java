package com.adelylria.ringlog.ui.components;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

public final class OpenStreetMapTileStoreTest {

    private OpenStreetMapTileStoreTest() {
    }

    public static void freshTilesAreReusedWithoutAnotherRequest() throws Exception {
        Path root = Files.createTempDirectory("ringlog-map-cache-");
        try {
            AtomicInteger requests = new AtomicInteger();
            byte[] tile = pngTile();
            Clock clock = Clock.fixed(Instant.parse("2026-09-10T10:00:00Z"), ZoneOffset.UTC);
            OpenStreetMapTileStore first = new OpenStreetMapTileStore(
                    root,
                    uri -> {
                        requests.incrementAndGet();
                        require(uri.toString().equals(
                                        "https://tile.openstreetmap.org/15/16656/12462.png"),
                                "The official HTTPS tile endpoint should be used");
                        return tile;
                    },
                    clock
            );
            require(first.load(15, 16656, 12462) != null,
                    "The downloaded tile should be decoded");

            OpenStreetMapTileStore second = new OpenStreetMapTileStore(
                    root,
                    uri -> {
                        requests.incrementAndGet();
                        throw new IOException("A fresh cache entry must avoid the network");
                    },
                    clock
            );
            require(second.load(15, 16656, 12462) != null,
                    "The cached tile should be readable");
            require(requests.get() == 1,
                    "A tile must be cached for at least seven days");
        } finally {
            deleteTree(root);
        }
    }

    public static void staleTilesRemainAvailableWhenOffline() throws Exception {
        Path root = Files.createTempDirectory("ringlog-map-offline-");
        try {
            Instant now = Instant.parse("2026-09-10T10:00:00Z");
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);
            OpenStreetMapTileStore seed = new OpenStreetMapTileStore(
                    root,
                    uri -> pngTile(),
                    clock
            );
            seed.load(15, 16656, 12462);
            Files.setLastModifiedTime(
                    seed.cachePath(15, 16656, 12462),
                    FileTime.from(now.minus(OpenStreetMapTileStore.CACHE_LIFETIME).minusSeconds(1))
            );

            OpenStreetMapTileStore offline = new OpenStreetMapTileStore(
                    root,
                    uri -> {
                        throw new IOException("offline");
                    },
                    clock
            );
            require(offline.load(15, 16656, 12462) != null,
                    "An expired cached tile should remain useful while offline");
        } finally {
            deleteTree(root);
        }
    }

    public static void invalidTileCoordinatesAreRejectedBeforeDownload() throws Exception {
        Path root = Files.createTempDirectory("ringlog-map-invalid-");
        try {
            AtomicInteger requests = new AtomicInteger();
            OpenStreetMapTileStore store = new OpenStreetMapTileStore(
                    root,
                    uri -> {
                        requests.incrementAndGet();
                        return pngTile();
                    },
                    Clock.systemUTC()
            );
            try {
                store.load(15, -1, 0);
                throw new AssertionError("Invalid coordinates should fail");
            } catch (IllegalArgumentException expected) {
                require(requests.get() == 0,
                        "Invalid coordinates must not produce a network request");
            }
        } finally {
            deleteTree(root);
        }
    }

    private static byte[] pngTile() throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        freshTilesAreReusedWithoutAnotherRequest();
        staleTilesRemainAvailableWhenOffline();
        invalidTileCoordinatesAreRejectedBeforeDownload();
        System.out.println("OpenStreetMapTileStoreTest: PASS");
    }
}
