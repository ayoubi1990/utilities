package dev.velo.core;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** Asks for the first byte only: a 206 with a total size means parallel ranges and resume work. */
final class Probe {
    /**
     * @param finalUri the URL after redirects; segment requests go straight here
     * @param total    file size, or -1 if unknown
     * @param body     the full response when the server ignored the range, so a
     *                 single-stream download can reuse it instead of asking again
     */
    record Info(URI finalUri, long total, boolean ranges, String fileName, String etag, String lastModified,
            Http.Exchange body) {}

    private Probe() {}

    static Info run(URI uri, Map<String, String> headers) throws VeloException {
        Http.Exchange resp;
        try {
            resp = Http.get(uri, headers, "bytes=0-0", null);
        } catch (IOException e) {
            throw VeloException.network(e);
        }
        if (!resp.isSuccess()) {
            resp.close();
            throw VeloException.status(resp.status);
        }
        URI finalUri = resp.uri;
        String disposition = resp.header("Content-Disposition");
        String fileName = disposition != null ? FileNames.fromContentDisposition(disposition) : null;
        if (fileName == null) {
            fileName = FileNames.fromUri(finalUri);
        }
        if (fileName == null) {
            fileName = "download";
        }
        String etag = resp.header("ETag");
        String lastModified = resp.header("Last-Modified");

        if (resp.status == 206) {
            String contentRange = resp.header("Content-Range");
            resp.close();
            long total = contentRange != null ? contentRangeTotal(contentRange) : -1;
            return new Info(finalUri, total, total >= 0, fileName, etag, lastModified, null);
        }
        return new Info(finalUri, resp.contentLength(), false, fileName, etag, lastModified, resp);
    }

    /** Parses the total from {@code bytes 0-0/12345}; {@code *} (unknown) yields -1. */
    static long contentRangeTotal(String value) {
        int slash = value.lastIndexOf('/');
        try {
            return slash < 0 ? -1 : Long.parseLong(value.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
