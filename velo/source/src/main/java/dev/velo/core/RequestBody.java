package dev.velo.core;

/**
 * A download that is not a plain GET: the method and body the browser sent, e.g. the form
 * SharePoint/OneDrive POSTs to its zip service to download a folder. Every request for the
 * download (probe and each connection) repeats it.
 *
 * @param method      HTTP method, e.g. "POST"
 * @param data        the body bytes, or null for none
 * @param contentType the body's Content-Type, e.g. "application/x-www-form-urlencoded"
 */
public record RequestBody(String method, byte[] data, String contentType) {
    public RequestBody {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method is required");
        }
    }
}
