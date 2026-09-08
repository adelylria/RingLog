package com.adelylria.ringlog.update;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

public final class UpdateCheckerTest {

    private UpdateCheckerTest() {
    }

    public static void signedLatestPairProducesAnArchitectureSpecificUpdate() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] manifest = manifest("1.0.1");
        byte[] signature = sign(pair, manifest);
        try (ServerFixture server = new ServerFixture(manifest, signature)) {
            UpdateChecker checker = checker(server, pair, UpdateArchitecture.WINDOWS_X64);
            UpdateCheckResult result = checker.check().get(5, TimeUnit.SECONDS);
            require(result.status() == UpdateCheckResult.Status.UPDATE_AVAILABLE,
                    "A newer signed version must be offered");
            require(result.asset() != null, "The matching architecture asset is required");
        }
    }

    public static void manifestAndSignatureFromDifferentReleasesAreRejected() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] manifest = manifest("1.0.2");
        byte[] otherSignature = sign(pair, manifest("1.0.1"));
        try (ServerFixture server = new ServerFixture(manifest, otherSignature)) {
            try {
                checker(server, pair, UpdateArchitecture.WINDOWS_X64)
                        .check().join();
                throw new AssertionError("A mismatched latest manifest/signature pair was accepted");
            } catch (CompletionException expected) {
                require(root(expected) instanceof UpdateException,
                        "Signature mismatch must surface as an update failure");
            }
        }
    }

    private static UpdateChecker checker(
            ServerFixture server,
            KeyPair pair,
            UpdateArchitecture architecture
    ) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return new UpdateChecker(
                client,
                server.uri("/update-manifest.json"),
                server.uri("/update-manifest.json.sig"),
                new UpdateManifestParser(),
                UpdateSignatureVerifier.fromX509(pair.getPublic().getEncoded()),
                architecture,
                new SemanticVersion(1, 0, 0),
                true
        );
    }

    private static byte[] manifest(String version) {
        return ("""
                {"format":"RingLog Update","formatVersion":1,"version":"%s","assets":{
                  "windows-x64":{
                    "url":"https://github.com/example/releases/download/v%s/RingLog-Setup-x64.exe",
                    "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "size":12345
                  }
                }}
                """).formatted(version, version).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sign(KeyPair pair, byte[] bytes) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(bytes);
        return signer.sign();
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
        signedLatestPairProducesAnArchitectureSpecificUpdate();
        manifestAndSignatureFromDifferentReleasesAreRejected();
        System.out.println("UpdateCheckerTest: PASS");
    }

    private static final class ServerFixture implements AutoCloseable {
        private final HttpServer server;

        private ServerFixture(byte[] manifest, byte[] signature) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/update-manifest.json", exchange -> {
                exchange.sendResponseHeaders(200, manifest.length);
                exchange.getResponseBody().write(manifest);
                exchange.close();
            });
            server.createContext("/update-manifest.json.sig", exchange -> {
                exchange.sendResponseHeaders(200, signature.length);
                exchange.getResponseBody().write(signature);
                exchange.close();
            });
            server.start();
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
