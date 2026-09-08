package com.adelylria.ringlog.build;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/** Exercises the same release-version command that a future GitHub Action will call. */
public final class ReleaseVersionToolTest {

    private ReleaseVersionToolTest() {
    }

    public static void tagParserAcceptsValidReleaseTag() throws Exception {
        require(runTool("1.2.3", "v1.2.3") == 0,
                "A matching stable SemVer tag must be accepted");
    }

    public static void tagParserRejectsInvalidTag() throws Exception {
        for (String invalid : new String[]{"1.2.3", "v1.2", "v01.2.3", "v1.2.3-rc1"}) {
            require(runTool("1.2.3", invalid) != 0,
                    "Invalid release tag should be rejected: " + invalid);
        }
    }

    public static void tagMismatchIsRejected() throws Exception {
        require(runTool("1.2.3", "v1.2.4") != 0,
                "A tag cannot publish a different Maven version");
    }

    public static void snapshotVersionCannotBeReleased() throws Exception {
        require(runTool("1.2.3-SNAPSHOT", "v1.2.3") != 0,
                "A SNAPSHOT build cannot be released from a stable tag");
    }

    private static int runTool(String version, String tag) throws Exception {
        Path temporary = Files.createTempDirectory("ringlog-release-version-");
        try {
            Path pom = temporary.resolve("pom.xml");
            Files.writeString(pom, """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>test</groupId>
                      <artifactId>test</artifactId>
                      <version>%s</version>
                    </project>
                    """.formatted(version), StandardCharsets.UTF_8);
            Path script = Path.of(System.getProperty("user.dir"),
                    "scripts", "ValidateReleaseVersion.java").toAbsolutePath();
            require(Files.isRegularFile(script),
                    "scripts/ValidateReleaseVersion.java must be reusable by CI");
            Process process = new ProcessBuilder(
                    javaExecutable().toString(), script.toString(), pom.toString(), tag
            ).redirectErrorStream(true).start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            require(finished, "Release version validator timed out");
            process.getInputStream().readAllBytes();
            return process.exitValue();
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        tagParserAcceptsValidReleaseTag();
        tagParserRejectsInvalidTag();
        tagMismatchIsRejected();
        snapshotVersionCannotBeReleased();
        System.out.println("ReleaseVersionToolTest: PASS");
    }
}
