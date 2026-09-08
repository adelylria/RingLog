package com.adelylria.ringlog.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/** Immutable public release-channel endpoints embedded at Maven build time. */
public record UpdateConfiguration(URI manifestUri, URI signatureUri) {

    public static final String BASE_URL_PROPERTY = "ringlog.update.base.url";
    private static final String RESOURCE = "/ringlog-update.properties";
    private static final String RESOURCE_KEY = "baseUrl";
    private static final Pattern GITHUB_LATEST = Pattern.compile(
            "^/[^/]+/[^/]+/releases/latest/download/?$"
    );

    public UpdateConfiguration {
        manifestUri = requireLatestAssetUri(manifestUri, "update-manifest.json");
        signatureUri = requireLatestAssetUri(signatureUri, "update-manifest.json.sig");
        String manifestBase = basePath(manifestUri, "update-manifest.json");
        String signatureBase = basePath(signatureUri, "update-manifest.json.sig");
        if (!manifestBase.equals(signatureBase)) {
            throw new IllegalArgumentException("Manifest and signature must use one release channel");
        }
    }

    public static Optional<UpdateConfiguration> application() {
        String override = text(System.getProperty(BASE_URL_PROPERTY));
        String baseUrl = override == null ? resourceBaseUrl() : override;
        if (baseUrl == null) {
            return Optional.empty();
        }
        return Optional.of(fromBaseUrl(baseUrl));
    }

    public static UpdateConfiguration fromBaseUrl(String value) {
        String base = text(value);
        if (base == null) {
            throw new IllegalArgumentException("Update base URL is required");
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URI channel = URI.create(base);
        if (!"https".equalsIgnoreCase(channel.getScheme())
                || !"github.com".equalsIgnoreCase(channel.getHost())
                || channel.getUserInfo() != null
                || channel.getQuery() != null
                || channel.getFragment() != null
                || !GITHUB_LATEST.matcher(channel.getPath()).matches()) {
            throw new IllegalArgumentException(
                    "The update channel must be a GitHub latest/download HTTPS URL"
            );
        }
        return new UpdateConfiguration(
                URI.create(base + "/update-manifest.json"),
                URI.create(base + "/update-manifest.json.sig")
        );
    }

    private static URI requireLatestAssetUri(URI uri, String asset) {
        if (uri == null
                || !"https".equalsIgnoreCase(uri.getScheme())
                || !"github.com".equalsIgnoreCase(uri.getHost())
                || !uri.getPath().endsWith("/releases/latest/download/" + asset)) {
            throw new IllegalArgumentException("Invalid GitHub latest asset URL");
        }
        return uri;
    }

    private static String basePath(URI uri, String asset) {
        String text = uri.toString();
        return text.substring(0, text.length() - asset.length() - 1);
    }

    private static String resourceBaseUrl() {
        Properties properties = new Properties();
        try (InputStream input = UpdateConfiguration.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                return null;
            }
            properties.load(input);
            return text(properties.getProperty(RESOURCE_KEY));
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static String text(String value) {
        if (value == null || value.isBlank() || value.contains("${")) {
            return null;
        }
        return value.strip();
    }
}
