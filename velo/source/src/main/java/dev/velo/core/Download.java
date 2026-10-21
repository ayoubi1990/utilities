package dev.velo.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A running download. Call {@link #cancel()} to pause it with its progress saved. */
public final class Download {
    private final Path path;
    private final long total;
    private final boolean resumable;
    private final long resumedFrom;
    private final Runnable canceller;
    final CompletableFuture<Path> result = new CompletableFuture<>();
    private volatile Progress progress;

    Download(Path path, long total, boolean resumable, long resumedFrom, Runnable canceller) {
        this.path = path;
        this.total = total;
        this.resumable = resumable;
        this.resumedFrom = resumedFrom;
        this.canceller = canceller;
        this.progress = Progress.initial(total);
    }

    public Path path() {
        return path;
    }

    /** File size in bytes, or -1 if unknown. */
    public long total() {
        return total;
    }

    /** Whether the server supports ranges, so the download can be paused and resumed. */
    public boolean resumable() {
        return resumable;
    }

    /** Bytes already on disk from an earlier, interrupted run. */
    public long resumedFrom() {
        return resumedFrom;
    }

    public Progress progress() {
        return progress;
    }

    void publish(Progress p) {
        progress = p;
    }

    public void cancel() {
        canceller.run();
    }

    public boolean isDone() {
        return result.isDone();
    }

    /** Waits for the download and returns the saved file's path. */
    public Path await() throws VeloException, InterruptedException {
        try {
            return result.get();
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    /** Like {@link #await()} but gives up after {@code timeout}, returning null. */
    public Path await(Duration timeout) throws VeloException, InterruptedException {
        try {
            return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw unwrap(e);
        } catch (TimeoutException e) {
            return null;
        }
    }

    private static VeloException unwrap(ExecutionException e) {
        if (e.getCause() instanceof VeloException v) {
            return v;
        }
        return VeloException.network(e.getCause() instanceof Exception ex ? ex : e);
    }
}
