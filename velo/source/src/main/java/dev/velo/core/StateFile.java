package dev.velo.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Everything needed to resume a download, stored next to it as
 * {@code <name>.velo} while the data goes to {@code <name>.part}.
 */
final class StateFile {
    private static final String VERSION = "1";

    record Saved(String url, long total, String etag, String lastModified, List<Segment> segments) {
        Saved withSegments(List<Segment> segments) {
            return new Saved(url, total, etag, lastModified, segments);
        }
    }

    private StateFile() {}

    static Path partPath(Path path) {
        return path.resolveSibling(path.getFileName() + ".part");
    }

    static Path statePath(Path path) {
        return path.resolveSibling(path.getFileName() + ".velo");
    }

    /** A missing, unreadable or outdated file just means starting over, so it reads as null. */
    static Saved load(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            p.load(r);
            if (!VERSION.equals(p.getProperty("version"))) {
                return null;
            }
            int count = Integer.parseInt(p.getProperty("segments"));
            List<Segment> segments = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String[] f = p.getProperty("segment." + i).split(",");
                segments.add(new Segment(Long.parseLong(f[0]), Long.parseLong(f[1]), Long.parseLong(f[2])));
            }
            return new Saved(
                    p.getProperty("url"),
                    Long.parseLong(p.getProperty("total")),
                    p.getProperty("etag"),
                    p.getProperty("lastModified"),
                    segments);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Writes to a temporary file first so a crash never leaves a torn state. */
    static void save(Path path, Saved state) throws IOException {
        Properties p = new Properties();
        p.setProperty("version", VERSION);
        p.setProperty("url", state.url());
        p.setProperty("total", Long.toString(state.total()));
        if (state.etag() != null) {
            p.setProperty("etag", state.etag());
        }
        if (state.lastModified() != null) {
            p.setProperty("lastModified", state.lastModified());
        }
        p.setProperty("segments", Integer.toString(state.segments().size()));
        for (int i = 0; i < state.segments().size(); i++) {
            Segment s = state.segments().get(i);
            p.setProperty("segment." + i, s.start() + "," + s.end() + "," + s.written());
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(w, "velo download state");
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
    }
}
