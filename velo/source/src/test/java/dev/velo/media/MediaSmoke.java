package dev.velo.media;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * End-to-end check against a real page with the installed yt-dlp:
 * MediaSmoke URL OUTPUT_DIR. Downloads the lowest video quality offered.
 */
public final class MediaSmoke {
    private MediaSmoke() {}

    public static void main(String[] args) throws Exception {
        // MediaSmoke URL OUTPUT_DIR [BUNDLED_TOOLS_DIR]
        Path bundled = args.length > 2 ? Path.of(args[2]) : null;
        YtDlp.Tools tools = YtDlp.detect(bundled, Files.createTempDirectory("velo-smoke-user"));
        System.out.println("tools: " + (tools == null ? null : tools.describe() + " " + tools.launcher()));
        if (tools == null) {
            System.exit(2);
        }
        MediaInfo info = YtDlp.probe(tools, args[0], Map.of());
        System.out.println("title: " + info.title() + " (" + info.site() + ", " + (long) info.durationSeconds() + "s)");
        info.options().forEach(o -> System.out.println("  option: " + o.label() + "  ~" + o.sizeEstimate()));
        MediaInfo.Option smallest = info.options().stream().filter(o -> !o.audioOnly())
                .reduce((a, b) -> b).orElseThrow();
        System.out.println("downloading: " + smallest.label());
        MediaJob job = new MediaJob(tools, args[0], smallest.selector(), false, Path.of(args[1]), Map.of(), 4);
        Thread t = Thread.ofPlatform().start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                System.out.printf("  %s %d/%d %.0f B/s%n", job.stage(), job.downloaded(), job.total(), job.speed());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        Path file = job.run();
        t.interrupt();
        System.out.println("saved: " + file + " (" + Files.size(file) + " bytes)");
    }
}
