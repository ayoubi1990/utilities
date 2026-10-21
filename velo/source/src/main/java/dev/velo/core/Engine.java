package dev.velo.core;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Entry point of the download engine. One engine (and its shared speed
 * limiter) can run any number of downloads at once.
 */
public final class Engine {
    private final RateLimiter limiter;

    public Engine() {
        this(new RateLimiter(0));
    }

    public Engine(RateLimiter limiter) {
        this.limiter = limiter;
    }

    public RateLimiter limiter() {
        return limiter;
    }

    /**
     * Probes the URL, picks the output path, resumes from a matching state
     * file if there is one, and starts transferring in the background.
     */
    public Download start(String url, Destination dest, DownloadOptions opts) throws VeloException {
        URI uri = parse(url);
        Probe.Info info = Probe.run(uri, opts.headers());

        Path preferred;
        boolean explicit;
        switch (dest) {
            case Destination.Dir d -> {
                preferred = d.dir().resolve(FileNames.sanitize(info.fileName()));
                explicit = false;
            }
            case Destination.File f -> {
                preferred = f.file();
                explicit = true;
            }
        }
        try {
            Path parent = preferred.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            Http.closeQuietly(info.body());
            throw VeloException.io(e);
        }

        if (!info.ranges()) {
            Path path = explicit ? preferred : uniquePath(preferred);
            SingleJob job = new SingleJob(limiter, info.finalUri(), opts.headers(), info.body(), path);
            Download download = new Download(path, info.total(), false, 0, job::cancel);
            launch(download, () -> job.run(download));
            return download;
        }

        long total = info.total();
        SegmentTable table = null;
        StateFile.Saved saved = StateFile.load(StateFile.statePath(preferred));
        if (saved != null && sameResource(saved, info) && Files.exists(StateFile.partPath(preferred))) {
            table = SegmentTable.fromSaved(saved.segments(), total, opts.minSplit());
        }
        boolean resumed = table != null;
        Path path = resumed || explicit ? preferred : uniquePath(preferred);
        if (!resumed) {
            table = SegmentTable.create(total, opts.connections(), opts.minSplit());
        }

        Path part = StateFile.partPath(path);
        FileChannel channel;
        try {
            channel = resumed ? SegmentedJob.openExisting(part, total) : SegmentedJob.createPreallocated(part, total);
        } catch (IOException e) {
            throw VeloException.io(e);
        }
        StateFile.Saved template = new StateFile.Saved(url, total, info.etag(), info.lastModified(), List.of());
        SegmentedJob job = new SegmentedJob(limiter, info.finalUri(), opts, table, channel, template, path);
        try {
            job.save();
        } catch (IOException e) {
            Http.closeQuietly(channel);
            throw VeloException.io(e);
        }
        Download download = new Download(path, total, true, table.downloaded(), job::cancel);
        launch(download, () -> job.run(download));
        return download;
    }

    private static URI parse(String url) throws VeloException {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw VeloException.invalidUrl(url);
            }
            return uri;
        } catch (URISyntaxException e) {
            throw VeloException.invalidUrl(url);
        }
    }

    private static boolean sameResource(StateFile.Saved saved, Probe.Info info) {
        return saved.total() == info.total()
                && Objects.equals(saved.etag(), info.etag())
                && Objects.equals(saved.lastModified(), info.lastModified());
    }

    /** Adds " (1)", " (2)"… until neither the file nor its .part exists. */
    static Path uniquePath(Path path) {
        if (!taken(path)) {
            return path;
        }
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            Path candidate = path.resolveSibling(stem + " (" + i + ")" + ext);
            if (!taken(candidate)) {
                return candidate;
            }
        }
    }

    private static boolean taken(Path p) {
        return Files.exists(p) || Files.exists(StateFile.partPath(p));
    }

    private interface Task {
        Path run() throws VeloException;
    }

    private static void launch(Download download, Task task) {
        Thread.ofPlatform().daemon().name("velo-download").start(() -> {
            try {
                download.result.complete(task.run());
            } catch (Throwable t) {
                download.result.completeExceptionally(t);
            }
        });
    }
}
