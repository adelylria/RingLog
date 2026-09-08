package com.adelylria.ringlog.update;

public record DownloadProgress(long downloadedBytes, long totalBytes) {
    public DownloadProgress {
        if (downloadedBytes < 0 || totalBytes <= 0 || downloadedBytes > totalBytes) {
            throw new IllegalArgumentException("Invalid download progress");
        }
    }
}
