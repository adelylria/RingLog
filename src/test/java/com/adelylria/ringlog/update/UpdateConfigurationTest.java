package com.adelylria.ringlog.update;

public final class UpdateConfigurationTest {

    private UpdateConfigurationTest() {
    }

    public static void stableLatestAssetUrlsAreDerivedTogether() {
        UpdateConfiguration configuration = UpdateConfiguration.fromBaseUrl(
                "https://github.com/example/RingLog-Releases/releases/latest/download"
        );
        require(configuration.manifestUri().toString().endsWith(
                        "/releases/latest/download/update-manifest.json"),
                "Manifest must use GitHub's stable latest asset URL");
        require(configuration.signatureUri().toString().endsWith(
                        "/releases/latest/download/update-manifest.json.sig"),
                "Signature must use the matching stable latest asset URL");
    }

    public static void apiAndNonGithubChannelsAreRejected() {
        for (String value : new String[]{
                "https://api.github.com/repos/example/releases/latest",
                "https://example.com/releases/latest/download",
                "http://github.com/example/repo/releases/latest/download"
        }) {
            try {
                UpdateConfiguration.fromBaseUrl(value);
                throw new AssertionError("Invalid update channel accepted: " + value);
            } catch (IllegalArgumentException expected) {
                // Expected.
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        stableLatestAssetUrlsAreDerivedTogether();
        apiAndNonGithubChannelsAreRejected();
        System.out.println("UpdateConfigurationTest: PASS");
    }
}
