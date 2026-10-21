package dev.velo.media;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * One yt-dlp download. Progress arrives on yt-dlp's output through custom
 * templates; pausing stops the process, and running the same job again
 * continues from yt-dlp's partial files.
 */
public final class MediaJob {
    private static final String PROGRESS = "download:VELO|%(progress.status)s|%(progress.downloaded_bytes)s"
            + "|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.filename)s";
    private static final String POSTPROCESS = "postprocess:VELOPP|%(progress.postprocessor)s|%(progress.status)s";
    private static final String DONE = "after_move:VELOFILE|%(filepath)s";

    private final YtDlp.Tools tools;
    private final String url;
    private final String selector;
    private final boolean audioOnly;
    private final Path dir;
    private final Map<String, String> headers;
    private final int fragments;

    private volatile Process process;
    private volatile boolean cancelled;
    private volatile long downloaded;
    private volatile long total = -1;
    private volatile double speed;
    private volatile String stage = "Starting…";
    private volatile Path file;
    private volatile String lastError;

    // Bytes of earlier parts (video before audio) that are already finished.
    private long finishedParts;
    private long currentPartTotal;
    private String currentPart;
    private int partNumber;

    public MediaJob(YtDlp.Tools tools, String url, String selector, boolean audioOnly, Path dir,
            Map<String, String> headers, int fragments) {
        this.tools = tools;
        this.url = url;
        this.selector = selector;
        this.audioOnly = audioOnly;
        this.dir = dir;
        this.headers = headers;
        this.fragments = Math.max(1, Math.min(16, fragments));
    }

    public long downloaded() {
        return downloaded;
    }

    /** Bytes expected so far, or -1; grows when a second part (audio) starts. */
    public long total() {
        return total;
    }

    public double speed() {
        return speed;
    }

    public String stage() {
        return stage;
    }

    List<String> command() {
        List<String> cmd = YtDlp.base(tools);
        cmd.addAll(List.of("-f", selector, "--no-playlist", "--newline", "--progress", "--no-simulate",
                "--print", DONE, "--progress-template", PROGRESS, "--progress-template", POSTPROCESS,
                "-N", Integer.toString(fragments), "--windows-filenames",
                "-P", dir.toString(), "-o", "%(title).150B [%(id)s].%(ext)s"));
        if (!audioOnly && tools.ffmpeg()) {
            cmd.addAll(List.of("--merge-output-format", "mp4"));
        }
        cmd.addAll(YtDlp.headerArgs(headers));
        cmd.addAll(List.of("--", url));
        return cmd;
    }

    /** Downloads, blocking until done; returns the saved file. */
    public Path run() throws IOException {
        Process p = YtDlp.builder(command()).redirectErrorStream(true).start();
        process = p;
        if (cancelled) {
            cancel();
        }
        StringBuilder log = new StringBuilder();
        Thread reader = YtDlp.pump(p.getInputStream(), log, this::onLine);
        int code;
        try {
            code = p.waitFor();
            reader.join(3000);
        } catch (InterruptedException e) {
            cancel();
            Thread.currentThread().interrupt();
            throw new CancellationException();
        }
        speed = 0;
        if (cancelled) {
            throw new CancellationException();
        }
        if (code != 0) {
            throw new IOException(lastError != null ? lastError : "yt-dlp stopped with code " + code);
        }
        if (file == null) {
            throw new IOException("yt-dlp finished but did not report the file");
        }
        return file;
    }

    public void cancel() {
        cancelled = true;
        Process p = process;
        if (p != null) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    void onLine(String line) {
        if (line.startsWith("VELO|")) {
            progress(line.split("\\|", 7));
        } else if (line.startsWith("VELOPP|")) {
            String[] f = line.split("\\|");
            if (f.length >= 3 && f[2].equals("started")) {
                stage = f[1].contains("Merger") ? "Merging video and audio…" : "Finishing (" + f[1] + ")…";
                speed = 0;
            }
        } else if (line.startsWith("VELOFILE|")) {
            file = Path.of(line.substring(9).strip());
        } else if (line.startsWith("ERROR:")) {
            lastError = line.substring(6).strip();
        }
    }

    private void progress(String[] f) {
        if (f.length < 7) {
            return;
        }
        String part = f[6];
        if (!part.equals(currentPart)) {
            if (currentPart != null) {
                finishedParts += currentPartTotal;
            }
            currentPart = part;
            currentPartTotal = 0;
            partNumber++;
        }
        long dl = num(f[2]);
        long partTotal = Math.max(num(f[3]), num(f[4]));
        if (partTotal > 0) {
            currentPartTotal = partTotal;
        }
        downloaded = finishedParts + Math.max(0, dl);
        total = currentPartTotal > 0 ? finishedParts + currentPartTotal : -1;
        speed = Math.max(0, num(f[5]));
        String what = audioOnly ? "audio" : partNumber == 1 ? "video" : "audio";
        stage = f[1].equals("finished") ? "Downloaded " + what : "Receiving " + what + "…";
    }

    private static long num(String s) {
        try {
            return (long) Double.parseDouble(s.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public Path file() {
        return file;
    }
}
