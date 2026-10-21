package dev.velo.core;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * Downloads from servers without range support over one connection. These
 * cannot be resumed, so a failed or cancelled attempt is discarded.
 */
final class SingleJob {
    private static final int BUFFER = 256 * 1024;
    private static final long PUBLISH_NANOS = 250_000_000L;

    private final RateLimiter limiter;
    private final URI uri;
    private final Map<String, String> headers;
    private final Http.Exchange probeResponse;
    private final Path path;
    private final Http.Canceller canceller = new Http.Canceller();

    SingleJob(RateLimiter limiter, URI uri, Map<String, String> headers, Http.Exchange probeResponse, Path path) {
        this.limiter = limiter;
        this.uri = uri;
        this.headers = headers;
        this.probeResponse = probeResponse;
        this.path = path;
    }

    void cancel() {
        canceller.cancel();
        if (probeResponse != null) {
            probeResponse.close();
        }
    }

    Path run(Download download) throws VeloException {
        Path part = StateFile.partPath(path);
        try {
            stream(part, download);
            Files.move(part, path, StandardCopyOption.REPLACE_EXISTING);
            return path;
        } catch (VeloException e) {
            deleteQuietly(part);
            throw e;
        } catch (IOException e) {
            deleteQuietly(part);
            throw VeloException.io(e);
        }
    }

    private void stream(Path part, Download download) throws VeloException {
        Http.Exchange resp = probeResponse != null ? probeResponse : open();
        long expected = resp.contentLength();
        long downloaded = 0;
        Http.SpeedMeter meter = new Http.SpeedMeter(0);
        long lastPublish = System.nanoTime();
        try (resp; OutputStream out = new BufferedOutputStream(Files.newOutputStream(part), BUFFER)) {
            if (!resp.isSuccess()) {
                throw VeloException.status(resp.status);
            }
            InputStream in = resp.body();
            byte[] buf = new byte[64 * 1024];
            while (true) {
                int n = in.read(buf);
                if (canceller.isCancelled()) {
                    throw VeloException.cancelled();
                }
                if (n < 0) {
                    break;
                }
                limiter.acquire(n);
                out.write(buf, 0, n);
                downloaded += n;
                long now = System.nanoTime();
                if (now - lastPublish >= PUBLISH_NANOS) {
                    download.publish(new Progress(expected, downloaded, meter.sample(downloaded), 1, List.of()));
                    lastPublish = now;
                }
            }
        } catch (IOException e) {
            throw canceller.isCancelled() ? VeloException.cancelled() : VeloException.network(e);
        } catch (InterruptedException e) {
            throw VeloException.cancelled();
        }
        download.publish(new Progress(expected, downloaded, meter.sample(downloaded), 0, List.of()));
        if (expected >= 0 && downloaded < expected) {
            throw VeloException.incomplete();
        }
    }

    private Http.Exchange open() throws VeloException {
        try {
            return Http.get(uri, headers, null, canceller);
        } catch (IOException e) {
            throw canceller.isCancelled() ? VeloException.cancelled() : VeloException.network(e);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // Leaving a stray .part file behind is harmless.
        }
    }
}
