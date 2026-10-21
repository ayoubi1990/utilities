package dev.velo.media;

import static dev.velo.core.TestMain.check;
import static dev.velo.core.TestMain.eq;
import static dev.velo.core.TestMain.test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class MediaTests {
    private MediaTests() {}

    /** Trimmed-down yt-dlp -J output: DASH video in two codecs at two heights, plus audio. */
    private static final String INFO = """
            {"id":"abc","title":"Big \\"Buck\\" Bunny","extractor_key":"Youtube","duration":635.0,
             "formats":[
              {"format_id":"140","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","tbr":129,"filesize":10276044},
              {"format_id":"251","ext":"webm","vcodec":"none","acodec":"opus","tbr":129,"filesize":10200000},
              {"format_id":"136","ext":"mp4","vcodec":"avc1.4d401f","acodec":"none","height":720,"fps":30,"tbr":1898,
               "filesize":150000000,"protocol":"https"},
              {"format_id":"311","ext":"mp4","vcodec":"avc1.4D4020","acodec":"none","height":720,"fps":60,"tbr":4554,
               "protocol":"m3u8_native"},
              {"format_id":"302","ext":"webm","vcodec":"vp9","acodec":"none","height":720,"fps":60,"tbr":1421,
               "filesize":112000000},
              {"format_id":"401","ext":"mp4","vcodec":"av01.0.13M.08","acodec":"none","height":2160,"fps":60,
               "tbr":8982,"filesize_approx":712000000},
              {"format_id":"18","ext":"mp4","vcodec":"avc1.42001E","acodec":"mp4a.40.2","height":360,"tbr":500,
               "filesize":null}
             ]}""";

    public static void run() {
        test("json reader handles nesting, escapes and numbers", () -> {
            Object v = JsonReader.parse("{\"a\":[1,2.5,-3e2,true,null,\"x\\u00e9\"],\"b\":{}}");
            Map<?, ?> m = (Map<?, ?>) v;
            List<?> a = (List<?>) m.get("a");
            eq(a.get(0), 1.0);
            eq(a.get(2), -300.0);
            eq(a.get(3), Boolean.TRUE);
            eq(a.get(4), null);
            eq(a.get(5), "xé");
            check(((Map<?, ?>) m.get("b")).isEmpty(), "empty object");
        });
        test("quality options with ffmpeg", () -> {
            MediaInfo info = MediaInfo.fromJson(INFO, true);
            eq(info.title(), "Big \"Buck\" Bunny");
            eq(info.site(), "Youtube");
            List<MediaInfo.Option> o = info.options();
            eq(o.stream().map(MediaInfo.Option::label).toList(),
                    List.of("2160p60  (MP4, VP9/AV1)", "720p60  (MP4)", "360p  (MP4)", "Audio only  (M4A)"));
            // 720p picks the H.264 stream for compatibility, plus the m4a audio.
            eq(o.get(1).sizeEstimate(), 150000000L + 10276044L);
            check(o.get(1).selector().startsWith("bv*[height<=720][vcodec^=avc1]+ba[ext=m4a]"), o.get(1).selector());
            check(o.get(3).audioOnly(), "audio option last");
        });
        test("quality options without ffmpeg are pre-merged only", () -> {
            List<MediaInfo.Option> o = MediaInfo.fromJson(INFO, false).options();
            eq(o.get(0).selector(), "18");
            eq(o.get(0).label(), "360p  (MP4)");
            eq(o.size(), 2);
        });
        test("yt-dlp progress lines", () -> {
            MediaJob job = new MediaJob(new YtDlp.Tools(List.of("yt-dlp.exe"), "t", true, null, null, true),
                    "https://example.com/v", "b", false, Path.of("."), Map.of(), 4);
            job.onLine("[youtube] abc: Downloading webpage");
            job.onLine("VELO|downloading|1000|4000|NA|500.0|v.f136.mp4");
            eq(job.downloaded(), 1000L);
            eq(job.total(), 4000L);
            eq(job.stage(), "Receiving video…");
            job.onLine("VELO|finished|4000|4000|NA|NA|v.f136.mp4");
            job.onLine("VELO|downloading|200|NA|1000|300.5|v.f140.m4a");
            eq(job.downloaded(), 4200L);
            eq(job.total(), 5000L);
            eq(job.stage(), "Receiving audio…");
            job.onLine("VELOPP|Merger|started");
            eq(job.stage(), "Merging video and audio…");
            job.onLine("VELOFILE|C:\\Videos\\Big Buck Bunny [abc].mp4");
            eq(job.file(), Path.of("C:\\Videos\\Big Buck Bunny [abc].mp4"));
            job.onLine("ERROR: [youtube] abc: Video unavailable");
        });
        test("yt-dlp command line", () -> {
            MediaJob job = new MediaJob(new YtDlp.Tools(List.of("C:\\velo\\tools\\yt-dlp.exe"), "t", true,
                    "C:\\velo\\tools", "deno:C:\\velo\\tools\\deno.exe", true), "https://example.com/v",
                    "bv*+ba/b", false, Path.of("D:/Videos"), Map.of("Referer", "https://example.com/"), 4);
            List<String> cmd = job.command();
            eq(cmd.get(0), "C:\\velo\\tools\\yt-dlp.exe");
            check(cmd.containsAll(List.of("--ffmpeg-location", "C:\\velo\\tools", "--js-runtimes",
                    "deno:C:\\velo\\tools\\deno.exe", "--merge-output-format", "mp4", "--referer")), cmd.toString());
            eq(cmd.get(cmd.size() - 1), "https://example.com/v");
            eq(cmd.get(cmd.size() - 2), "--");
        });
    }
}
