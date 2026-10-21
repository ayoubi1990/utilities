package dev.velo.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP over {@link HttpURLConnection}. It uses plain blocking sockets, which
 * keeps working on machines where security software breaks the NIO selector
 * that {@code java.net.http.HttpClient} depends on.
 */
final class Http {
    static final String USER_AGENT = "velo/0.1";
    static final int CONNECT_TIMEOUT_MS = 15_000;
    /** A connection that delivers nothing for this long is treated as dead and retried. */
    static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_REDIRECTS = 10;

    private Http() {}

    /**
     * Sends a GET, following redirects across http and https (which
     * HttpURLConnection refuses to do on its own).
     */
    static Exchange get(URI uri, Map<String, String> headers, String range, Canceller canceller) throws IOException {
        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection c = (HttpURLConnection) current.toURL().openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("User-Agent", USER_AGENT);
            // Compressed bodies would make byte ranges meaningless.
            c.setRequestProperty("Accept-Encoding", "identity");
            headers.forEach(c::setRequestProperty);
            if (range != null) {
                c.setRequestProperty("Range", range);
            }
            if (canceller != null) {
                canceller.register(c);
            }
            int status;
            try {
                status = c.getResponseCode();
            } catch (IOException e) {
                abort(c, canceller);
                throw e;
            }
            String location = c.getHeaderField("Location");
            if (isRedirect(status) && location != null) {
                abort(c, canceller);
                try {
                    current = current.resolve(location.trim());
                } catch (IllegalArgumentException e) {
                    throw new IOException("bad redirect location: " + location, e);
                }
                continue;
            }
            return new Exchange(c, status, current, canceller);
        }
        throw new IOException("too many redirects");
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static void abort(HttpURLConnection c, Canceller canceller) {
        if (canceller != null) {
            canceller.unregister(c);
        }
        c.disconnect();
    }

    static void closeQuietly(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException ignored) {
            // Nothing useful to do; the connection is being abandoned anyway.
        }
    }

    /** One response. {@link #close()} drops the connection, so call it only once the body is no longer needed. */
    static final class Exchange implements Closeable {
        final int status;
        /** The URL after redirects. */
        final URI uri;
        private final HttpURLConnection conn;
        private final Canceller canceller;

        private Exchange(HttpURLConnection conn, int status, URI uri, Canceller canceller) {
            this.conn = conn;
            this.status = status;
            this.uri = uri;
            this.canceller = canceller;
        }

        boolean isSuccess() {
            return status >= 200 && status <= 299;
        }

        String header(String name) {
            return conn.getHeaderField(name);
        }

        /** Content-Length, or -1 if the server did not send one. */
        long contentLength() {
            return conn.getContentLengthLong();
        }

        InputStream body() throws IOException {
            return conn.getInputStream();
        }

        @Override
        public void close() {
            abort(conn, canceller);
        }
    }

    /** Lets another thread abort every connection of a job, including one still waiting for headers. */
    static final class Canceller {
        private final Set<HttpURLConnection> active = ConcurrentHashMap.newKeySet();
        private volatile boolean cancelled;

        boolean isCancelled() {
            return cancelled;
        }

        void cancel() {
            cancelled = true;
            active.forEach(HttpURLConnection::disconnect);
        }

        void register(HttpURLConnection c) {
            active.add(c);
            if (cancelled) {
                c.disconnect();
            }
        }

        void unregister(HttpURLConnection c) {
            active.remove(c);
        }
    }

    /** Exponentially smoothed transfer rate. */
    static final class SpeedMeter {
        private long lastAt = System.nanoTime();
        private long lastBytes;
        private double speed;

        SpeedMeter(long bytes) {
            this.lastBytes = bytes;
        }

        double sample(long bytes) {
            long now = System.nanoTime();
            double dt = (now - lastAt) / 1e9;
            if (dt >= 0.05) {
                double instant = Math.max(0, bytes - lastBytes) / dt;
                speed = speed == 0 ? instant : 0.7 * speed + 0.3 * instant;
                lastAt = now;
                lastBytes = bytes;
            }
            return speed;
        }
    }
}
