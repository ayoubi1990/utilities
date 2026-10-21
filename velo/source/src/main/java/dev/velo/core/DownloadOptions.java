package dev.velo.core;

import java.util.Map;

/**
 * @param connections maximum parallel connections for this download
 * @param minSplit    ranges are never split into pieces smaller than this many bytes
 * @param maxRetries  consecutive failures a connection tolerates before giving up
 * @param headers     extra request headers such as Cookie or Referer
 */
public record DownloadOptions(int connections, long minSplit, int maxRetries, Map<String, String> headers) {
    public DownloadOptions {
        headers = Map.copyOf(headers);
    }

    public static DownloadOptions defaults() {
        return new DownloadOptions(8, 1L << 20, 5, Map.of());
    }

    public DownloadOptions withConnections(int connections) {
        return new DownloadOptions(connections, minSplit, maxRetries, headers);
    }

    public DownloadOptions withMinSplit(long minSplit) {
        return new DownloadOptions(connections, minSplit, maxRetries, headers);
    }

    public DownloadOptions withMaxRetries(int maxRetries) {
        return new DownloadOptions(connections, minSplit, maxRetries, headers);
    }

    public DownloadOptions withHeaders(Map<String, String> headers) {
        return new DownloadOptions(connections, minSplit, maxRetries, headers);
    }
}
