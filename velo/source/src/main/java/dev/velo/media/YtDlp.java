package dev.velo.media;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs yt-dlp for pages whose video is streamed rather than served as a file.
 * velo only drives the tool; it contains no site-specific extraction of its own.
 *
 * <p>Packaged builds ship yt-dlp.exe, ffmpeg and deno in a {@code tools} folder, so users
 * install nothing. yt-dlp is copied to the user's data folder before use so it can update
 * itself even when velo is installed somewhere read-only. A Python install with the
 * yt_dlp module is used as a fallback (handy when running from source).
 */
public final class YtDlp {
    /** For developers running from source without the bundled tools. */
    public static final String INSTALL_HINT = "python -m pip install --user --upgrade \"yt-dlp[default]\"";

    /**
     * What velo found.
     *
     * @param launcher       how to start yt-dlp, e.g. [C:\...\yt-dlp.exe] or [python, -m, yt_dlp]
     * @param ffmpeg         whether ffmpeg is usable (needed to merge separate video and audio)
     * @param ffmpegLocation folder of a bundled ffmpeg, or null to use the one on PATH
     * @param jsRuntime      value for --js-runtimes (e.g. "deno:C:\...\deno.exe", "node"), or null
     * @param bundled        true when yt-dlp came with velo (it can be updated with -U)
     */
    public record Tools(List<String> launcher, String version, boolean ffmpeg, String ffmpegLocation,
            String jsRuntime, boolean bundled) {
        public String describe() {
            return "yt-dlp " + version + (bundled ? " (bundled)" : " (Python)")
                    + (ffmpeg ? ", ffmpeg" : ", no ffmpeg")
                    + (jsRuntime != null ? ", " + jsRuntime.replaceFirst(":.*", "") : ", no JavaScript runtime");
        }
    }

    private YtDlp() {}

    public static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
            .startsWith("windows");

    /** A bundled program's file name on this OS: "yt-dlp.exe" on Windows, "yt-dlp" elsewhere. */
    public static String exe(String name) {
        return WINDOWS ? name + ".exe" : name;
    }

    /**
     * Finds working tools, trying each candidate by running it.
     *
     * @param bundledDir velo's own tools folder (may be null or missing when running from source)
     * @param userDir    a writable per-user folder for the updatable copy of yt-dlp
     * @return the tools, or null if no working yt-dlp was found
     */
    public static Tools detect(Path bundledDir, Path userDir) {
        Path bundledExe = bundledDir == null ? null : bundledDir.resolve(exe("yt-dlp"));
        String version = null;
        List<String> launcher = null;
        boolean bundled = false;

        if (bundledExe != null && Files.isRegularFile(bundledExe)) {
            Path copy = userDir.resolve(exe("yt-dlp"));
            try {
                String bundledVersion = version(List.of(bundledExe.toString()));
                String copyVersion = Files.isRegularFile(copy) ? version(List.of(copy.toString())) : null;
                // yt-dlp versions are dates (2026.08.19), so they compare as strings.
                if (bundledVersion != null && (copyVersion == null || bundledVersion.compareTo(copyVersion) > 0)) {
                    Files.createDirectories(userDir);
                    Files.copy(bundledExe, copy, StandardCopyOption.REPLACE_EXISTING);
                    if (!copy.toFile().setExecutable(true)) {
                        throw new IOException("cannot make " + copy + " executable");
                    }
                    copyVersion = bundledVersion;
                }
                if (copyVersion != null) {
                    launcher = List.of(copy.toString());
                    version = copyVersion;
                } else if (bundledVersion != null) {
                    launcher = List.of(bundledExe.toString());
                    version = bundledVersion;
                }
                bundled = launcher != null;
            } catch (IOException e) {
                // Fall through to Python.
            }
        }
        if (launcher == null) {
            for (String python : new String[] {"python", "py", "python3"}) {
                List<String> candidate = List.of(python, "-m", "yt_dlp");
                version = version(candidate);
                if (version != null) {
                    launcher = candidate;
                    break;
                }
            }
        }
        if (launcher == null) {
            return null;
        }

        // ffmpeg: bundled first, then whatever is on PATH.
        String ffmpegLocation = null;
        boolean ffmpeg = false;
        if (bundledDir != null && works(bundledDir.resolve(exe("ffmpeg")).toString(), "-version")) {
            ffmpeg = true;
            ffmpegLocation = bundledDir.toString();
        } else if (works("ffmpeg", "-version")) {
            ffmpeg = true;
        }

        // JavaScript runtime (YouTube): bundled deno, then deno or node on PATH.
        String js = null;
        if (bundledDir != null && works(bundledDir.resolve(exe("deno")).toString(), "--version")) {
            js = "deno:" + bundledDir.resolve(exe("deno"));
        } else if (works("deno", "--version")) {
            js = "deno";
        } else if (works("node", "--version")) {
            js = "node";
        }
        return new Tools(launcher, version, ffmpeg, ffmpegLocation, js, bundled);
    }

    /** Updates yt-dlp in place and returns its report. */
    public static String update(Tools tools) throws IOException {
        List<String> cmd = new ArrayList<>();
        if (tools.bundled()) {
            cmd.addAll(tools.launcher());
            cmd.add("-U");
        } else {
            cmd.addAll(List.of(tools.launcher().get(0), "-m", "pip", "install", "--user", "--upgrade",
                    "yt-dlp[default]"));
        }
        Result r = run(cmd, Duration.ofMinutes(5), null);
        String out = (r.stdout + "\n" + r.stderr).strip();
        if (r.exitCode != 0) {
            throw new IOException(out.isEmpty() ? "update failed with code " + r.exitCode : out);
        }
        return out;
    }

    private static String version(List<String> launcher) {
        try {
            List<String> cmd = new ArrayList<>(launcher);
            cmd.add("--version");
            Result r = run(cmd, Duration.ofSeconds(30), null);
            String v = r.stdout.strip();
            return r.exitCode == 0 && !v.isEmpty() && !v.contains("\n") ? v : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean works(String... cmd) {
        try {
            return run(List.of(cmd), Duration.ofSeconds(15), null).exitCode == 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** Reads the page's title and formats without downloading anything. */
    public static MediaInfo probe(Tools tools, String url, Map<String, String> headers) throws IOException {
        List<String> cmd = base(tools);
        cmd.addAll(List.of("-J", "--no-playlist", "--no-warnings"));
        cmd.addAll(headerArgs(headers));
        cmd.addAll(List.of("--", url));
        Result r = run(cmd, Duration.ofSeconds(120), null);
        if (r.exitCode != 0) {
            throw new IOException(errorFrom(r.stderr, "yt-dlp could not read this page"));
        }
        try {
            return MediaInfo.fromJson(r.stdout.strip(), tools.ffmpeg());
        } catch (RuntimeException e) {
            throw new IOException("Unexpected output from yt-dlp: " + e.getMessage(), e);
        }
    }

    static List<String> base(Tools tools) {
        List<String> cmd = new ArrayList<>(tools.launcher());
        cmd.addAll(List.of("--encoding", "utf-8", "--no-colors", "--ignore-config"));
        if (tools.ffmpegLocation() != null) {
            cmd.addAll(List.of("--ffmpeg-location", tools.ffmpegLocation()));
        }
        if (tools.jsRuntime() != null) {
            cmd.addAll(List.of("--js-runtimes", tools.jsRuntime()));
        }
        return cmd;
    }

    /** Passes on the browser's referrer and user agent; cookies are not handed to yt-dlp. */
    static List<String> headerArgs(Map<String, String> headers) {
        List<String> args = new ArrayList<>();
        if (headers.containsKey("Referer")) {
            args.addAll(List.of("--referer", headers.get("Referer")));
        }
        if (headers.containsKey("User-Agent")) {
            args.addAll(List.of("--user-agent", headers.get("User-Agent")));
        }
        return args;
    }

    static String errorFrom(String output, String fallback) {
        String last = null;
        for (String line : output.split("\\R")) {
            if (line.startsWith("ERROR:")) {
                last = line.substring(6).strip();
            }
        }
        return last != null ? last : fallback;
    }

    record Result(int exitCode, String stdout, String stderr) {}

    static ProcessBuilder builder(List<String> cmd) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("PYTHONUTF8", "1");
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        return pb;
    }

    /** Runs a command to completion, collecting output; lines are also streamed to {@code onLine} if given. */
    static Result run(List<String> cmd, Duration timeout, Consumer<String> onLine) throws IOException {
        Process p = builder(cmd).start();
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread t1 = pump(p.getInputStream(), out, onLine);
        Thread t2 = pump(p.getErrorStream(), err, onLine);
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                throw new IOException("yt-dlp did not answer in time");
            }
            t1.join(2000);
            t2.join(2000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        return new Result(p.exitValue(), out.toString(), err.toString());
    }

    static Thread pump(InputStream in, StringBuilder sink, Consumer<String> onLine) {
        return Thread.ofPlatform().daemon().start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    synchronized (sink) {
                        sink.append(line).append('\n');
                    }
                    if (onLine != null) {
                        onLine.accept(line);
                    }
                }
            } catch (IOException ignored) {
                // The process ended.
            }
        });
    }
}
