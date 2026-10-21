package dev.velo.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Downloads a file over parallel range requests. Each connection claims a
 * range; when it finishes it takes over half of the largest range still in
 * progress, so slow connections never hold up the end of the download.
 */
final class SegmentedJob {
    /** Bytes buffered per connection before they are written to disk. */
    private static final int FLUSH_AT = 256 * 1024;
    private static final int CHUNK = 64 * 1024;
    private static final long TICK_MS = 250;
    private static final int SAVE_EVERY_TICKS = 4;

    private final RateLimiter limiter;
    private final URI uri;
    private final Map<String, String> headers;
    private final int connections;
    private final int maxRetries;
    private final SegmentTable table;
    private final FileChannel channel;
    private final StateFile.Saved template;
    private final Path path;
    private final Http.Canceller canceller = new Http.Canceller();

    SegmentedJob(RateLimiter limiter, URI uri, DownloadOptions opts, SegmentTable table, FileChannel channel,
            StateFile.Saved template, Path path) {
        this.limiter = limiter;
        this.uri = uri;
        this.headers = opts.headers();
        this.connections = Math.max(1, opts.connections());
        this.maxRetries = opts.maxRetries();
        this.table = table;
        this.channel = channel;
        this.template = template;
        this.path = path;
    }

    /** Creates the .part file at full size so every connection can write at its own offset. */
    static FileChannel createPreallocated(Path part, long total) throws IOException {
        Files.deleteIfExists(part);
        // SPARSE stops NTFS from zero-filling everything before the furthest write.
        FileChannel ch = FileChannel.open(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                StandardOpenOption.WRITE, StandardOpenOption.SPARSE);
        if (total > 0) {
            ch.write(ByteBuffer.wrap(new byte[1]), total - 1);
        }
        return ch;
    }

    static FileChannel openExisting(Path part, long total) throws IOException {
        FileChannel ch = FileChannel.open(part, StandardOpenOption.READ, StandardOpenOption.WRITE);
        if (ch.size() > total) {
            ch.truncate(total);
        } else if (ch.size() < total && total > 0) {
            ch.write(ByteBuffer.wrap(new byte[1]), total - 1);
        }
        return ch;
    }

    void cancel() {
        canceller.cancel();
    }

    void save() throws IOException {
        StateFile.save(StateFile.statePath(path), template.withSegments(table.snapshot()));
    }

    Path run(Download download) throws VeloException {
        VeloException lastError = null;
        // Platform threads: HttpURLConnection blocks inside synchronized code,
        // which would pin (and starve) virtual-thread carriers on JDK 21.
        try (ExecutorService exec = Executors.newThreadPerTaskExecutor(
                Thread.ofPlatform().daemon().name("velo-conn-", 0).factory())) {
            List<Future<Void>> workers = new ArrayList<>();
            for (int i = 0; i < connections; i++) {
                workers.add(exec.submit(() -> {
                    worker();
                    return null;
                }));
            }
            Http.SpeedMeter meter = new Http.SpeedMeter(table.downloaded());
            int ticks = 0;
            while (!workers.stream().allMatch(Future::isDone)) {
                try {
                    Thread.sleep(TICK_MS);
                } catch (InterruptedException e) {
                    cancel();
                }
                download.publish(progress(meter));
                if (++ticks % SAVE_EVERY_TICKS == 0) {
                    trySave();
                }
            }
            for (Future<Void> f : workers) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    lastError = e.getCause() instanceof VeloException v ? v
                            : VeloException.network(e.getCause() instanceof Exception ex ? ex : e);
                } catch (InterruptedException e) {
                    cancel();
                }
            }
            download.publish(progress(meter));
        }

        boolean complete = table.isComplete();
        try {
            // Windows refuses to rename a file that is still open.
            channel.close();
            if (!complete) {
                save();
            }
        } catch (IOException e) {
            throw VeloException.io(e);
        }
        if (!complete) {
            if (canceller.isCancelled()) {
                throw VeloException.cancelled();
            }
            throw lastError != null ? lastError : VeloException.incomplete();
        }
        try {
            Files.move(StateFile.partPath(path), path, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(StateFile.statePath(path));
        } catch (IOException e) {
            throw VeloException.io(e);
        }
        return path;
    }

    /** One connection: claims ranges until nothing is left, retrying failures with exponential backoff. */
    private void worker() throws VeloException {
        int failures = 0;
        while (true) {
            if (canceller.isCancelled()) {
                throw VeloException.cancelled();
            }
            int idx = table.claim();
            if (idx < 0) {
                return;
            }
            long before = table.written(idx);
            try {
                fetchSegment(idx);
                failures = 0;
            } catch (VeloException e) {
                table.release(idx);
                if (canceller.isCancelled() || e.kind() == VeloException.Kind.CANCELLED) {
                    throw VeloException.cancelled();
                }
                failures = table.written(idx) > before ? 1 : failures + 1;
                if (failures > maxRetries) {
                    throw e;
                }
                backoff(failures);
            }
        }
    }

    private void backoff(int failures) throws VeloException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250L << Math.min(failures, 6));
        while (System.nanoTime() < deadline) {
            if (canceller.isCancelled()) {
                throw VeloException.cancelled();
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw VeloException.cancelled();
            }
        }
    }

    private void fetchSegment(int idx) throws VeloException {
        long[] range = table.pendingRange(idx);
        long from = range[0];
        long to = range[1];
        if (from >= to) {
            return;
        }
        Http.Exchange resp;
        try {
            resp = Http.get(uri, headers, "bytes=" + from + "-" + (to - 1), canceller);
        } catch (IOException e) {
            throw canceller.isCancelled() ? VeloException.cancelled() : VeloException.network(e);
        }

        ByteBuffer buf = ByteBuffer.allocate(FLUSH_AT + CHUNK);
        long[] offset = {from};
        VeloException failure = null;
        try (resp) {
            if (resp.status != 206) {
                throw resp.isSuccess() ? VeloException.rangeNotHonored() : VeloException.status(resp.status);
            }
            InputStream in = resp.body();
            byte[] chunk = new byte[CHUNK];
            while (true) {
                int n = in.read(chunk);
                if (n < 0) {
                    break;
                }
                limiter.acquire(n);
                int granted = (int) table.reserve(idx, n);
                buf.put(chunk, 0, granted);
                if (buf.position() >= FLUSH_AT) {
                    flush(idx, buf, offset);
                }
                if (granted < n) {
                    // Another connection took over the rest of this range.
                    break;
                }
            }
        } catch (IOException e) {
            failure = VeloException.network(e);
        } catch (InterruptedException e) {
            failure = VeloException.cancelled();
        } catch (VeloException e) {
            failure = e;
        }
        // Keep whatever arrived, even when stopping on an error or cancel.
        flush(idx, buf, offset);
        if (canceller.isCancelled()) {
            throw VeloException.cancelled();
        }
        if (failure != null) {
            throw failure;
        }
        if (!table.isSegmentDone(idx)) {
            throw VeloException.incomplete();
        }
    }

    private void flush(int idx, ByteBuffer buf, long[] offset) throws VeloException {
        if (buf.position() == 0) {
            return;
        }
        buf.flip();
        int len = buf.remaining();
        try {
            long pos = offset[0];
            while (buf.hasRemaining()) {
                pos += channel.write(buf, pos);
            }
        } catch (IOException e) {
            buf.clear();
            throw VeloException.io(e);
        }
        table.commit(idx, len);
        offset[0] += len;
        buf.clear();
    }

    private void trySave() {
        try {
            save();
        } catch (IOException ignored) {
            // A failed periodic save only costs progress after a crash.
        }
    }

    private Progress progress(Http.SpeedMeter meter) {
        long downloaded = table.downloaded();
        return new Progress(template.total(), downloaded, meter.sample(downloaded), table.active(), table.snapshot());
    }
}
