package com.adelylria.ringlog.update;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

public final class UpdateDownloadServiceTest {

    private UpdateDownloadServiceTest() {
    }

    public static void verifiedInstallerIsPublishedWithoutPartialFile() throws Exception {
        byte[] installer = "verified setup bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path root = Files.createDirectories(Path.of(
                System.getProperty("ringlog.data.dir"), "update-download-success"
        ));
        try (ServerFixture server = new ServerFixture(installer)) {
            UpdateAsset asset = new UpdateAsset(server.uri(), sha256(installer), installer.length);
            Path downloaded = service(root).download(
                    asset, UpdateArchitecture.WINDOWS_X64, ignored -> { }
            ).get(5, TimeUnit.SECONDS);
            require(Files.isRegularFile(downloaded), "Verified installer was not published");
            require(downloaded.getFileName().toString().equals("RingLog-Setup-x64.exe"),
                    "Installer name must be architecture-specific and safe");
            require(Files.list(downloaded.getParent())
                            .noneMatch(path -> path.toString().endsWith(".part")),
                    "Partial file remained after successful verification");
        }
    }

    public static void hashMismatchDeletesTheTemporaryDownload() throws Exception {
        byte[] installer = "tampered setup".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path root = Files.createDirectories(Path.of(
                System.getProperty("ringlog.data.dir"), "update-download-failure"
        ));
        try (ServerFixture server = new ServerFixture(installer)) {
            UpdateAsset asset = new UpdateAsset(
                    server.uri(), "a".repeat(64), installer.length
            );
            try {
                service(root).download(asset, UpdateArchitecture.WINDOWS_X86, ignored -> { }).join();
                throw new AssertionError("A wrong installer hash was accepted");
            } catch (CompletionException expected) {
                require(root(expected) instanceof UpdateException,
                        "Hash mismatch must be an update failure");
            }
            try (var children = Files.list(root)) {
                require(children.findAny().isEmpty(),
                        "A failed download must remove its temporary directory");
            }
        }
    }

    private static UpdateDownloadService service(Path root) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return new UpdateDownloadService(client, true, root);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Throwable root(Throwable failure) {
        Throwable result = failure;
        while (result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        verifiedInstallerIsPublishedWithoutPartialFile();
        hashMismatchDeletesTheTemporaryDownload();
        System.out.println("UpdateDownloadServiceTest: PASS");
    }

    private static final class ServerFixture implements AutoCloseable {
        private final HttpServer server;

        private ServerFixture(byte[] installer) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/setup.exe", exchange -> {
                exchange.sendResponseHeaders(200, installer.length);
                exchange.getResponseBody().write(installer);
                exchange.close();
            });
            server.start();
        }

        private URI uri() {
            return URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/setup.exe"
            );
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
