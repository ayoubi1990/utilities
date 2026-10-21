package dev.velo.ui;

import dev.velo.core.Destination;
import dev.velo.core.Download;
import dev.velo.core.DownloadOptions;
import dev.velo.core.Engine;
import dev.velo.core.Progress;
import dev.velo.core.RateLimiter;
import dev.velo.core.VeloException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The download list behind the UI: queues items, runs at most
 * {@link #maxActive()} at once, and saves the list and settings between runs.
 */
final class DownloadManager {
    /** What the user asked for in the Download File Info dialog. */
    record Request(String url, Path dir, String fileName, int connections, boolean start, String description,
            Map<String, String> headers) {}

    /** A video page to fetch with yt-dlp, with the quality the user picked. */
    record MediaRequest(String pageUrl, Path dir, String title, String ext, String selector, String label,
            boolean audioOnly, long sizeEstimate, boolean start, Map<String, String> headers) {}

    static final String MEDIA_MISSING = "Video support is not available: velo's video tools (yt-dlp) are missing "
            + "or blocked on this PC. Reinstall velo, or use Help > Update video support.";

    /** Headers worth keeping across restarts; cookies stay in memory only. */
    private static final List<String> PERSISTED_HEADERS = List.of("Referer", "User-Agent");

    private final Path store;
    private final RateLimiter limiter = new RateLimiter(0);
    private final Engine engine = new Engine(limiter);
    private final List<DownloadItem> items = new CopyOnWriteArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<DownloadItem>> completionListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService runner =
            Executors.newCachedThreadPool(Thread.ofPlatform().daemon().name("velo-item-", 0).factory());
    private final AtomicLong nextId = new AtomicLong(1);
    private volatile boolean shuttingDown;
    private volatile dev.velo.media.YtDlp.Tools mediaTools;

    private volatile String theme = Theme.LIGHT;
    private volatile long speedLimit;
    private volatile int maxActive = 3;
    private volatile int defaultConnections = 8;
    private volatile Path defaultDir = Path.of(System.getProperty("user.home"), "Downloads");
    private volatile boolean showProgress = true;
    private volatile boolean showComplete = true;

    private DownloadManager(Path store) {
        this.store = store;
    }

    /**
     * %APPDATA%\velo on Windows, $XDG_DATA_HOME/velo (~/.local/share/velo) elsewhere, or the
     * folder given with -Dvelo.home=... (portable installs, tests).
     */
    static Path defaultStore() {
        String override = System.getProperty("velo.home");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().resolve("downloads.properties");
        }
        String appData = System.getenv("APPDATA");
        String xdg = System.getenv("XDG_DATA_HOME");
        Path base = appData != null ? Path.of(appData, "velo")
                : xdg != null && !xdg.isBlank() ? Path.of(xdg, "velo")
                : Path.of(System.getProperty("user.home"), ".local", "share", "velo");
        return base.resolve("downloads.properties");
    }

    static DownloadManager open(Path store) {
        DownloadManager m = new DownloadManager(store);
        m.load();
        return m;
    }

    // ---- items ------------------------------------------------------------------------------------

    List<DownloadItem> items() {
        return List.copyOf(items);
    }

    void addListener(Runnable listener) {
        listeners.add(listener);
    }

    void onCompleted(Consumer<DownloadItem> listener) {
        completionListeners.add(listener);
    }

    DownloadItem add(Request r) {
        DownloadItem item = new DownloadItem(nextId.getAndIncrement(), r.url(), r.dir(), r.connections(),
                System.currentTimeMillis(), r.start() ? DownloadItem.Status.QUEUED : DownloadItem.Status.PAUSED,
                r.description(), r.headers());
        if (r.fileName() != null && !r.fileName().isBlank()) {
            item.path = r.dir().resolve(r.fileName());
            item.name = r.fileName();
        }
        items.add(item);
        persist();
        fire();
        schedule();
        return item;
    }

    DownloadItem addMedia(MediaRequest r) {
        Map<String, String> headers = new LinkedHashMap<>(r.headers());
        headers.remove("Cookie"); // yt-dlp is not given browser cookies
        DownloadItem item = new DownloadItem(nextId.getAndIncrement(), r.pageUrl(), r.dir(), defaultConnections,
                System.currentTimeMillis(), r.start() ? DownloadItem.Status.QUEUED : DownloadItem.Status.PAUSED,
                r.title(), headers);
        item.kind = DownloadItem.Kind.MEDIA;
        item.mediaSelector = r.selector();
        item.mediaLabel = r.label();
        item.audioOnly = r.audioOnly();
        item.sizeEstimate = r.sizeEstimate();
        item.total = r.sizeEstimate();
        item.name = DownloadItem.safeName(r.title()) + "." + r.ext();
        items.add(item);
        persist();
        fire();
        schedule();
        return item;
    }

    /** The folder holding the download list, settings and the updatable copy of yt-dlp. */
    Path dataDir() {
        return store.toAbsolutePath().getParent();
    }

    dev.velo.media.YtDlp.Tools mediaTools() {
        return mediaTools;
    }

    void setMediaTools(dev.velo.media.YtDlp.Tools tools) {
        mediaTools = tools;
    }

    synchronized void resume(Collection<DownloadItem> selected) {
        for (DownloadItem item : selected) {
            if (item.status == DownloadItem.Status.PAUSED || item.status == DownloadItem.Status.FAILED) {
                item.status = DownloadItem.Status.QUEUED;
                item.error = null;
            }
        }
        persist();
        fire();
        schedule();
    }

    synchronized void pause(Collection<DownloadItem> selected) {
        for (DownloadItem item : selected) {
            switch (item.status) {
                case QUEUED -> item.status = DownloadItem.Status.PAUSED;
                case CONNECTING -> item.pauseRequested = true;
                case DOWNLOADING -> {
                    item.pauseRequested = true;
                    stop(item);
                }
                default -> { }
            }
        }
        persist();
        fire();
    }

    /** Stops whichever transfer is running for the item. */
    private static void stop(DownloadItem item) {
        Download d = item.handle;
        if (d != null) {
            d.cancel();
        }
        dev.velo.media.MediaJob m = item.media;
        if (m != null) {
            m.cancel();
        }
    }

    void pauseAll() {
        pause(items);
    }

    void resumeAll() {
        resume(items);
    }

    void removeCompleted() {
        remove(items.stream().filter(i -> i.status == DownloadItem.Status.COMPLETED).toList(), false);
    }

    synchronized void remove(Collection<DownloadItem> selected, boolean deleteFiles) {
        for (DownloadItem item : selected) {
            item.removed = true;
            item.deleteFiles = deleteFiles;
            items.remove(item);
            if (item.handle != null || item.media != null) {
                stop(item); // the worker deletes the files once the transfer has stopped
            } else if (item.status == DownloadItem.Status.CONNECTING) {
                item.pauseRequested = true;
            } else if (deleteFiles) {
                deleteFiles(item);
            }
        }
        persist();
        fire();
        schedule();
    }

    /** Copies live progress into the items; returns the combined speed in bytes per second. */
    double poll() {
        double total = 0;
        for (DownloadItem item : items) {
            Download d = item.handle;
            if (d != null) {
                copyProgress(item, d.progress());
                total += item.speed;
            }
            dev.velo.media.MediaJob m = item.media;
            if (m != null) {
                copyProgress(item, m);
                total += item.speed;
            }
        }
        return total;
    }

    /** Pauses everything that is running, waiting briefly so each download can save its resume state. */
    void shutdown() {
        shuttingDown = true;
        List<Download> running = new ArrayList<>();
        for (DownloadItem item : items) {
            item.pauseRequested = true;
            Download d = item.handle;
            if (d != null) {
                d.cancel();
                running.add(d);
            }
            dev.velo.media.MediaJob m = item.media;
            if (m != null) {
                // yt-dlp keeps its .part files; the next run continues them.
                m.cancel();
                item.status = DownloadItem.Status.PAUSED;
            }
        }
        for (Download d : running) {
            try {
                d.await(Duration.ofSeconds(5));
            } catch (VeloException | InterruptedException ignored) {
                // Paused or failed: either way it has stopped.
            }
        }
        persist();
    }

    private synchronized void schedule() {
        if (shuttingDown) {
            return;
        }
        long active = items.stream().filter(i -> i.status.isRunning()).count();
        for (DownloadItem item : items) {
            if (active >= maxActive) {
                break;
            }
            if (item.status == DownloadItem.Status.QUEUED) {
                item.status = DownloadItem.Status.CONNECTING;
                item.pauseRequested = false;
                runner.submit(() -> run(item));
                active++;
            }
        }
    }

    private void run(DownloadItem item) {
        item.lastTryAt = System.currentTimeMillis();
        fire();
        if (item.isMedia()) {
            runMedia(item);
            return;
        }
        boolean completed = false;
        try {
            Destination dest = item.path != null ? Destination.file(item.path) : Destination.dir(item.dir);
            Download d = engine.start(item.url, dest, DownloadOptions.defaults()
                    .withConnections(item.connections)
                    .withHeaders(item.headers));
            item.handle = d;
            item.path = d.path();
            item.name = d.path().getFileName().toString();
            item.total = d.total();
            item.resumable = d.resumable();
            item.downloaded = d.resumedFrom();
            item.status = DownloadItem.Status.DOWNLOADING;
            if (item.pauseRequested || item.removed) {
                d.cancel();
            }
            persist();
            fire();
            d.await();
            item.status = DownloadItem.Status.COMPLETED;
            item.completedAt = System.currentTimeMillis();
            completed = true;
        } catch (VeloException e) {
            boolean paused = e.kind() == VeloException.Kind.CANCELLED || item.pauseRequested;
            item.status = paused ? DownloadItem.Status.PAUSED : DownloadItem.Status.FAILED;
            item.error = paused ? null : e.getMessage();
        } catch (InterruptedException e) {
            item.status = DownloadItem.Status.PAUSED;
        } catch (RuntimeException e) {
            item.status = DownloadItem.Status.FAILED;
            item.error = e.getMessage();
        } finally {
            Download d = item.handle;
            if (d != null) {
                copyProgress(item, d.progress());
            }
            if (item.status == DownloadItem.Status.COMPLETED && item.total > 0) {
                item.downloaded = item.total;
            }
            item.handle = null;
            item.speed = 0;
            item.activeConnections = 0;
            item.pauseRequested = false;
            if (item.removed && item.deleteFiles) {
                deleteFiles(item);
            }
            persist();
            fire();
            schedule();
        }
        if (completed && !item.removed) {
            completionListeners.forEach(l -> l.accept(item));
        }
    }

    private void runMedia(DownloadItem item) {
        boolean completed = false;
        dev.velo.media.YtDlp.Tools tools = mediaTools;
        try {
            if (tools == null) {
                throw new IOException(MEDIA_MISSING);
            }
            dev.velo.media.MediaJob job = new dev.velo.media.MediaJob(tools, item.url, item.mediaSelector,
                    item.audioOnly, item.dir, item.headers, item.connections);
            item.media = job;
            item.status = DownloadItem.Status.DOWNLOADING;
            item.resumable = true;
            item.stage = job.stage();
            if (item.pauseRequested || item.removed) {
                job.cancel();
            }
            fire();
            Path file = job.run();
            item.path = file;
            item.name = file.getFileName().toString();
            item.status = DownloadItem.Status.COMPLETED;
            item.completedAt = System.currentTimeMillis();
            try {
                item.total = Files.size(file);
            } catch (IOException ignored) {
                // Keep the last known size.
            }
            completed = true;
        } catch (java.util.concurrent.CancellationException e) {
            item.status = DownloadItem.Status.PAUSED;
        } catch (IOException | RuntimeException e) {
            item.status = item.pauseRequested ? DownloadItem.Status.PAUSED : DownloadItem.Status.FAILED;
            item.error = item.pauseRequested ? null : e.getMessage();
        } finally {
            dev.velo.media.MediaJob m = item.media;
            if (m != null) {
                copyProgress(item, m);
            }
            if (item.status == DownloadItem.Status.COMPLETED) {
                item.downloaded = item.total;
            }
            item.media = null;
            item.speed = 0;
            item.activeConnections = 0;
            item.pauseRequested = false;
            item.stage = null;
            if (item.removed && item.deleteFiles) {
                deleteFiles(item);
            }
            persist();
            fire();
            schedule();
        }
        if (completed && !item.removed) {
            completionListeners.forEach(l -> l.accept(item));
        }
    }

    private static void copyProgress(DownloadItem item, dev.velo.media.MediaJob m) {
        item.downloaded = Math.max(0, m.downloaded());
        item.total = Math.max(item.sizeEstimate, m.total());
        item.speed = m.speed();
        item.stage = m.stage();
        item.activeConnections = 1;
        if (m.file() != null) {
            item.path = m.file();
        }
    }

    private static void copyProgress(DownloadItem item, Progress p) {
        item.downloaded = p.downloaded();
        if (p.total() >= 0) {
            item.total = p.total();
        }
        item.speed = p.speed();
        item.activeConnections = p.connections();
        item.segments = p.segments();
    }

    private static void deleteFiles(DownloadItem item) {
        Path p = item.path;
        if (p == null) {
            return;
        }
        for (Path f : List.of(p, p.resolveSibling(p.getFileName() + ".part"),
                p.resolveSibling(p.getFileName() + ".velo"))) {
            try {
                Files.deleteIfExists(f);
            } catch (IOException ignored) {
                // The file may be open elsewhere; leave it.
            }
        }
    }

    private void fire() {
        listeners.forEach(Runnable::run);
    }

    // ---- settings ---------------------------------------------------------------------------------

    /** "light", "dark" or "system" (see {@link Theme}). */
    String theme() {
        return theme;
    }

    void setTheme(String theme) {
        this.theme = theme;
        persist();
    }

    long speedLimit() {
        return speedLimit;
    }

    void setSpeedLimit(long bytesPerSecond) {
        speedLimit = bytesPerSecond;
        limiter.setRate(bytesPerSecond);
        persist();
    }

    int maxActive() {
        return maxActive;
    }

    void setMaxActive(int maxActive) {
        this.maxActive = Math.max(1, maxActive);
        persist();
        schedule();
    }

    int defaultConnections() {
        return defaultConnections;
    }

    void setDefaultConnections(int connections) {
        defaultConnections = Math.max(1, Math.min(32, connections));
        persist();
    }

    Path defaultDir() {
        return defaultDir;
    }

    void setDefaultDir(Path dir) {
        defaultDir = dir;
        persist();
    }

    /** IDM-style default: each category saves into its own sub-folder. */
    Path folderFor(DownloadItem.Type type) {
        return defaultDir.resolve(type.label);
    }

    boolean showProgress() {
        return showProgress;
    }

    void setShowProgress(boolean show) {
        showProgress = show;
        persist();
    }

    boolean showComplete() {
        return showComplete;
    }

    void setShowComplete(boolean show) {
        showComplete = show;
        persist();
    }

    // ---- persistence ------------------------------------------------------------------------------

    private synchronized void persist() {
        Properties p = new Properties();
        p.setProperty("settings.theme", theme);
        p.setProperty("settings.speedLimit", Long.toString(speedLimit));
        p.setProperty("settings.maxActive", Integer.toString(maxActive));
        p.setProperty("settings.connections", Integer.toString(defaultConnections));
        p.setProperty("settings.dir", defaultDir.toString());
        p.setProperty("settings.showProgress", Boolean.toString(showProgress));
        p.setProperty("settings.showComplete", Boolean.toString(showComplete));
        for (DownloadItem i : items) {
            String k = "item." + i.id + ".";
            p.setProperty(k + "url", i.url);
            p.setProperty(k + "dir", i.dir.toString());
            p.setProperty(k + "connections", Integer.toString(i.connections));
            p.setProperty(k + "added", Long.toString(i.addedAt));
            p.setProperty(k + "status", i.status.name());
            p.setProperty(k + "name", i.name);
            p.setProperty(k + "total", Long.toString(i.total));
            p.setProperty(k + "downloaded", Long.toString(i.downloaded));
            p.setProperty(k + "resumable", Boolean.toString(i.resumable));
            p.setProperty(k + "completed", Long.toString(i.completedAt));
            p.setProperty(k + "lastTry", Long.toString(i.lastTryAt));
            p.setProperty(k + "description", i.description);
            if (i.isMedia()) {
                p.setProperty(k + "kind", i.kind.name());
                p.setProperty(k + "media.selector", i.mediaSelector);
                p.setProperty(k + "media.label", i.mediaLabel == null ? "" : i.mediaLabel);
                p.setProperty(k + "media.audioOnly", Boolean.toString(i.audioOnly));
                p.setProperty(k + "media.estimate", Long.toString(i.sizeEstimate));
            }
            for (String h : PERSISTED_HEADERS) {
                if (i.headers.containsKey(h)) {
                    p.setProperty(k + "header." + h, i.headers.get(h));
                }
            }
            if (i.path != null) {
                p.setProperty(k + "path", i.path.toString());
            }
            if (i.error != null) {
                p.setProperty(k + "error", i.error);
            }
        }
        try {
            Files.createDirectories(store.getParent());
            Path tmp = store.resolveSibling(store.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                p.store(w, "velo downloads");
            }
            Files.move(tmp, store, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Losing the list is an inconvenience, not a reason to stop downloads.
        }
    }

    private void load() {
        if (!Files.isRegularFile(store)) {
            return;
        }
        try {
            // Keep the list as it was at startup, so a bad session can be undone by hand.
            Files.copy(store, store.resolveSibling(store.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // A missing backup is not a reason to refuse to start.
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(store, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            return;
        }
        // Earlier versions stored only "settings.dark".
        theme = p.getProperty("settings.theme",
                Boolean.parseBoolean(p.getProperty("settings.dark", "false")) ? Theme.DARK : Theme.LIGHT);
        speedLimit = parseLong(p.getProperty("settings.speedLimit"), 0);
        limiter.setRate(speedLimit);
        maxActive = (int) parseLong(p.getProperty("settings.maxActive"), 3);
        defaultConnections = (int) parseLong(p.getProperty("settings.connections"), 8);
        showProgress = Boolean.parseBoolean(p.getProperty("settings.showProgress", "true"));
        showComplete = Boolean.parseBoolean(p.getProperty("settings.showComplete", "true"));
        String dir = p.getProperty("settings.dir");
        if (dir != null) {
            defaultDir = Path.of(dir);
        }

        TreeSet<Long> ids = new TreeSet<>();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("item.") && key.endsWith(".url")) {
                ids.add(parseLong(key.substring(5, key.length() - 4), -1));
            }
        }
        for (long id : ids) {
            String k = "item." + id + ".";
            try {
                DownloadItem.Status status = DownloadItem.Status.valueOf(p.getProperty(k + "status"));
                // Nothing restarts on its own after a restart; the user resumes what they want.
                if (status != DownloadItem.Status.COMPLETED && status != DownloadItem.Status.FAILED) {
                    status = DownloadItem.Status.PAUSED;
                }
                Map<String, String> headers = new LinkedHashMap<>();
                for (String h : PERSISTED_HEADERS) {
                    String v = p.getProperty(k + "header." + h);
                    if (v != null) {
                        headers.put(h, v);
                    }
                }
                DownloadItem i = new DownloadItem(id, p.getProperty(k + "url"), Path.of(p.getProperty(k + "dir")),
                        (int) parseLong(p.getProperty(k + "connections"), 8),
                        parseLong(p.getProperty(k + "added"), 0), status, p.getProperty(k + "description", ""),
                        headers);
                i.name = p.getProperty(k + "name", i.name);
                i.total = parseLong(p.getProperty(k + "total"), -1);
                i.downloaded = parseLong(p.getProperty(k + "downloaded"), 0);
                i.resumable = Boolean.parseBoolean(p.getProperty(k + "resumable", "true"));
                i.completedAt = parseLong(p.getProperty(k + "completed"), 0);
                i.lastTryAt = parseLong(p.getProperty(k + "lastTry"), 0);
                i.error = p.getProperty(k + "error");
                if ("MEDIA".equals(p.getProperty(k + "kind"))) {
                    i.kind = DownloadItem.Kind.MEDIA;
                    i.mediaSelector = p.getProperty(k + "media.selector", "bv*+ba/b");
                    i.mediaLabel = p.getProperty(k + "media.label", "");
                    i.audioOnly = Boolean.parseBoolean(p.getProperty(k + "media.audioOnly", "false"));
                    i.sizeEstimate = parseLong(p.getProperty(k + "media.estimate"), -1);
                }
                String path = p.getProperty(k + "path");
                if (path != null) {
                    i.path = Path.of(path);
                }
                items.add(i);
                nextId.set(Math.max(nextId.get(), id + 1));
            } catch (RuntimeException ignored) {
                // Skip a corrupt entry rather than losing the whole list.
            }
        }
    }

    private static long parseLong(String s, long fallback) {
        try {
            return s == null ? fallback : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
