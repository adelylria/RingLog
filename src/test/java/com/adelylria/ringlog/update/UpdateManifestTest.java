package com.adelylria.ringlog.update;

import java.nio.charset.StandardCharsets;

public final class UpdateManifestTest {

    private UpdateManifestTest() {
    }

    public static void validManifestSelectsTheRequestedArchitecture() throws Exception {
        String json = """
                {
                  "format": "RingLog Update",
                  "formatVersion": 1,
                  "version": "1.0.10",
                  "assets": {
                    "windows-x64": {
                      "url": "https://github.com/example/releases/download/v1.0.10/RingLog-Setup-x64.exe",
                      "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "size": 12345
                    }
                  }
                }
                """;
        UpdateManifest manifest = new UpdateManifestParser().parse(
                json.getBytes(StandardCharsets.UTF_8)
        );
        require("1.0.10".equals(manifest.version().toString()), "Wrong manifest version");
        require(manifest.assetFor(UpdateArchitecture.WINDOWS_X64).isPresent(),
                "x64 asset was not parsed");
        require(manifest.assetFor(UpdateArchitecture.WINDOWS_X86).isEmpty(),
                "An absent architecture must remain absent");
    }

    public static void malformedOrUnsafeManifestIsRejected() {
        String[] invalid = {
                "{}",
                "{\"format\":\"Wrong\",\"formatVersion\":1,\"version\":\"1.0.1\",\"assets\":{}}",
                "{\"format\":\"RingLog Update\",\"formatVersion\":2,\"version\":\"1.0.1\",\"assets\":{}}",
                "{\"format\":\"RingLog Update\",\"formatVersion\":1,\"version\":\"1.0.1-rc1\",\"assets\":{}}",
                """
                {"format":"RingLog Update","formatVersion":1,"version":"1.0.1","assets":{
                  "windows-x86":{"url":"http://example.invalid/setup.exe","sha256":"aa","size":1}
                }}
                """
        };
        for (String json : invalid) {
            try {
                new UpdateManifestParser().parse(json.getBytes(StandardCharsets.UTF_8));
                throw new AssertionError("Invalid manifest accepted: " + json);
            } catch (UpdateException expected) {
                // Expected.
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        validManifestSelectsTheRequestedArchitecture();
        malformedOrUnsafeManifestIsRejected();
        System.out.println("UpdateManifestTest: PASS");
    }
}
