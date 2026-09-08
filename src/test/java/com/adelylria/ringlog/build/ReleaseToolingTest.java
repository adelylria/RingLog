package com.adelylria.ringlog.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import com.adelylria.ringlog.update.UpdateArchitecture;
import com.adelylria.ringlog.update.UpdateManifestParser;
import com.adelylria.ringlog.update.UpdateSignatureVerifier;

public final class ReleaseToolingTest {

    private ReleaseToolingTest() {
    }

    public static void generatedManifestAndSignatureAreAcceptedByRingLog() throws Exception {
        Path root = projectRoot();
        Path temporary = Files.createTempDirectory("ringlog-release-tools-")
                .toAbsolutePath().normalize();
        try {
            Files.writeString(temporary.resolve("pom.xml"), """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>test</groupId><artifactId>test</artifactId>
                      <version>1.2.3</version>
                    </project>
                    """, StandardCharsets.UTF_8);
            Path installer = temporary.resolve("RingLog-Setup-x64.exe");
            Files.writeString(installer, "sanitized installer fixture", StandardCharsets.UTF_8);
            Path privateKey = temporary.resolve("private.pk8");
            Path publicKey = temporary.resolve("public.der");
            Path manifest = temporary.resolve("update-manifest.json");
            Path signature = temporary.resolve("update-manifest.json.sig");

            run(root, "GenerateUpdateSigningKeys.java",
                    privateKey.toString(), publicKey.toString());
            run(root, "CreateUpdateManifest.java",
                    "--project-root", temporary.toString(),
                    "--repository", "example/RingLog",
                    "--installer", "x64=" + installer,
                    "--output", manifest.toString());
            run(root, "SignUpdateManifest.java",
                    privateKey.toString(), publicKey.toString(),
                    manifest.toString(), signature.toString());

            byte[] manifestBytes = Files.readAllBytes(manifest);
            require(UpdateSignatureVerifier.fromX509(Files.readAllBytes(publicKey))
                            .verify(manifestBytes, Files.readAllBytes(signature)),
                    "Release tooling produced an incompatible signature");
            var parsed = new UpdateManifestParser().parse(manifestBytes);
            require("1.2.3".equals(parsed.version().toString()), "Wrong release version");
            require(parsed.assetFor(UpdateArchitecture.WINDOWS_X64).isPresent(),
                    "Generated manifest lacks its x64 asset");
            require(parsed.assetFor(UpdateArchitecture.WINDOWS_X86).isEmpty(),
                    "Tool must not invent an unbuilt x86 asset");
        } finally {
            deleteTree(temporary);
        }
    }

    public static void keyGeneratorNeverOverwritesSigningMaterial() throws Exception {
        Path root = projectRoot();
        Path temporary = Files.createTempDirectory("ringlog-release-key-overwrite-")
                .toAbsolutePath().normalize();
        try {
            Path privateKey = temporary.resolve("private.pk8");
            Path publicKey = temporary.resolve("public.der");
            run(root, "GenerateUpdateSigningKeys.java",
                    privateKey.toString(), publicKey.toString());
            int exit = runExpectingFailure(root, "GenerateUpdateSigningKeys.java",
                    privateKey.toString(), publicKey.toString());
            require(exit != 0, "Signing keys were overwritten");
        } finally {
            deleteTree(temporary);
        }
    }

    private static void run(Path root, String script, String... args) throws Exception {
        int exit = runProcess(root, script, args);
        require(exit == 0, script + " failed with exit code " + exit);
    }

    private static int runExpectingFailure(Path root, String script, String... args)
            throws Exception {
        return runProcess(root, script, args);
    }

    private static int runProcess(Path root, String script, String... args) throws Exception {
        String[] command = new String[args.length + 2];
        command[0] = javaExecutable().toString();
        command[1] = root.resolve("scripts").resolve(script).toString();
        System.arraycopy(args, 0, command, 2, args.length);
        return new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor();
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private static Path projectRoot() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
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
        generatedManifestAndSignatureAreAcceptedByRingLog();
        keyGeneratorNeverOverwritesSigningMaterial();
        System.out.println("ReleaseToolingTest: PASS");
    }
}
