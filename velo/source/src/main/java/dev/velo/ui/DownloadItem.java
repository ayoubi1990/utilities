package dev.velo.ui;

import dev.velo.core.Download;
import dev.velo.core.Segment;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One row of the download list. Written by worker threads, read by the UI. */
final class DownloadItem {
    enum Status {
        QUEUED("Queued"),
        CONNECTING("Connecting…"),
        DOWNLOADING("Downloading"),
        PAUSED("Paused"),
        COMPLETED("Complete"),
        FAILED("Error");

        final String label;

        Status(String label) {
            this.label = label;
        }

        boolean isRunning() {
            return this == CONNECTING || this == DOWNLOADING;
        }
    }

    /** File categories, as in the sidebar; each gets its own sub-folder of the download folder. */
    enum Type {
        COMPRESSED("Compressed", Glyph.ZIP, "zip", "rar", "7z", "gz", "tgz", "bz2", "xz", "tar", "arj", "cab", "zst"),
        DOCUMENTS("Documents", Glyph.DOC, "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt",
                "csv", "epub", "md"),
        MUSIC("Music", Glyph.MUSIC, "mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus"),
        PROGRAMS("Programs", Glyph.APP, "exe", "msi", "msix", "iso", "img", "dmg", "apk", "deb", "rpm", "jar"),
        VIDEO("Video", Glyph.VIDEO, "mp4", "mkv", "avi", "mov", "wmv", "webm", "flv", "m4v", "ts", "3gp"),
        IMAGES("Images", Glyph.PHOTO, "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "tif", "tiff"),
        OTHER("General", Glyph.FILE);

        final String label;
        final char glyph;
        private final List<String> extensions;

        Type(String label, char glyph, String... extensions) {
            this.label = label;
            this.glyph = glyph;
            this.extensions = List.of(extensions);
        }

        static Type of(String fileName) {
            int dot = fileName.lastIndexOf('.');
            String ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
            for (Type t : values()) {
                if (t.extensions.contains(ext)) {
                    return t;
                }
            }
            return OTHER;
        }
    }

    /** FILE: fetched by velo's own engine. MEDIA: a video page handed to yt-dlp. */
    enum Kind { FILE, MEDIA }

    final long id;
    final String url;
    final Path dir;
    final long addedAt;
    final int connections;
    final String description;
    /** Extra request headers, e.g. Referer and Cookie handed over by the browser. */
    final Map<String, String> headers;

    volatile Path path;
    volatile String name;
    volatile long total = -1;
    volatile long downloaded;
    volatile double speed;
    volatile int activeConnections;
    volatile List<Segment> segments = List.of();
    volatile Status status;
    volatile String error;
    volatile boolean resumable = true;
    volatile long completedAt;
    volatile long lastTryAt;

    // Media (yt-dlp) downloads only.
    volatile Kind kind = Kind.FILE;
    volatile String mediaSelector;
    volatile String mediaLabel;
    volatile boolean audioOnly;
    volatile long sizeEstimate = -1;
    volatile String stage;

    // Owned by DownloadManager.
    volatile Download handle;
    volatile dev.velo.media.MediaJob media;
    volatile boolean pauseRequested;
    volatile boolean removed;
    volatile boolean deleteFiles;

    DownloadItem(long id, String url, Path dir, int connections, long addedAt, Status status, String description,
            Map<String, String> headers) {
        this.id = id;
        this.url = url;
        this.dir = dir;
        this.connections = connections;
        this.addedAt = addedAt;
        this.status = status;
        this.description = description == null ? "" : description;
        this.headers = Map.copyOf(headers);
        this.name = guessName(url);
    }

    Type type() {
        return Type.of(name);
    }

    /** Fraction done in [0, 1], or -1 when the size is unknown. */
    double fraction() {
        if (status == Status.COMPLETED) {
            return 1;
        }
        return total > 0 ? Math.min(1, (double) downloaded / total) : -1;
    }

    /** Seconds remaining, or -1 if unknown. */
    long etaSeconds() {
        if (status != Status.DOWNLOADING || speed < 1 || total <= 0) {
            return -1;
        }
        return (long) ((total - downloaded) / speed);
    }

    boolean isMedia() {
        return kind == Kind.MEDIA;
    }

    /** Turns a video title into a file name Windows accepts. */
    static String safeName(String title) {
        String s = title.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_").strip().replaceAll("[. ]+$", "");
        if (s.isEmpty()) {
            s = "video";
        }
        return s.length() > 150 ? s.substring(0, 150) : s;
    }

    /** A placeholder name shown until the server tells us the real one. */
    static String guessName(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path != null) {
                String[] parts = path.split("/");
                for (int i = parts.length - 1; i >= 0; i--) {
                    if (!parts[i].isBlank()) {
                        return parts[i];
                    }
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to a generic name.
        }
        return "download";
    }
}
