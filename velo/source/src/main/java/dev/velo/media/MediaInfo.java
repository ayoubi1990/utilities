package dev.velo.media;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/** What yt-dlp found on a page, and the download choices offered for it. */
public record MediaInfo(String title, String id, String site, double durationSeconds, List<Option> options) {

    /**
     * One quality choice.
     *
     * @param selector     yt-dlp format selector
     * @param sizeEstimate approximate bytes, or -1 if unknown
     * @param ext          file extension the result will have
     */
    public record Option(String label, String selector, long sizeEstimate, String ext, boolean audioOnly) {}

    /** A single format from yt-dlp's list. */
    record Format(String id, int height, double fps, String vcodec, String acodec, String ext, long size, double tbr,
            String protocol) {
        /** Plain HTTPS streams are what yt-dlp prefers over HLS variants of the same quality. */
        boolean isHttps() {
            return protocol != null && protocol.startsWith("http");
        }

        boolean hasVideo() {
            return vcodec != null && !vcodec.equals("none");
        }

        boolean hasAudio() {
            return acodec != null && !acodec.equals("none");
        }

        boolean isAvc() {
            return vcodec != null && vcodec.startsWith("avc1");
        }
    }

    /** Builds the info from yt-dlp's {@code -J} output. */
    @SuppressWarnings("unchecked")
    static MediaInfo fromJson(String json, boolean canMerge) {
        Map<String, Object> root = (Map<String, Object>) JsonReader.parse(json);
        List<Format> formats = new ArrayList<>();
        double duration = num(root.get("duration"));
        Object list = root.get("formats");
        if (list instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> f) {
                    formats.add(format((Map<String, Object>) f, duration));
                }
            }
        }
        if (formats.isEmpty() && root.get("url") != null) {
            // Single-format sites put the format fields on the root.
            formats.add(format(root, duration));
        }
        return new MediaInfo(
                str(root.get("title"), "video"),
                str(root.get("id"), ""),
                str(root.get("extractor_key"), str(root.get("extractor"), "")),
                num(root.get("duration")),
                options(formats, canMerge));
    }

    private static Format format(Map<String, Object> f, double duration) {
        double tbr = num(f.get("tbr"));
        long size = (long) Math.max(num(f.get("filesize")), num(f.get("filesize_approx")));
        if (size <= 0 && tbr > 0 && duration > 0) {
            size = (long) (tbr * 1000 / 8 * duration); // tbr is in kbit/s
        }
        return new Format(str(f.get("format_id"), ""), (int) num(f.get("height")), num(f.get("fps")),
                str(f.get("vcodec"), null), str(f.get("acodec"), null), str(f.get("ext"), "mp4"),
                size > 0 ? size : -1, tbr, str(f.get("protocol"), "https"));
    }

    /**
     * One option per available height (newest players' "quality" menu), preferring H.264 + AAC
     * so the result plays everywhere, plus an audio-only option. Without ffmpeg only formats
     * that already contain both video and audio can be offered.
     */
    static List<Option> options(List<Format> formats, boolean canMerge) {
        List<Option> out = new ArrayList<>();
        Format bestAudio = formats.stream().filter(f -> f.hasAudio() && !f.hasVideo())
                .max(Comparator.comparing((Format f) -> f.ext().equals("m4a")).thenComparing(Format::isHttps)
                        .thenComparingDouble(Format::tbr))
                .orElse(null);

        if (canMerge) {
            TreeSet<Integer> heights = new TreeSet<>(Comparator.reverseOrder());
            formats.stream().filter(f -> f.hasVideo() && f.height() > 0).forEach(f -> heights.add(f.height()));
            for (int h : heights) {
                List<Format> atHeight = formats.stream().filter(f -> f.hasVideo() && f.height() == h).toList();
                // Mirror what the selector will get: H.264 first, plain HTTPS over HLS, then bitrate.
                Comparator<Format> preference = Comparator.comparing(Format::isHttps)
                        .thenComparingDouble(Format::tbr);
                Format pick = atHeight.stream().filter(Format::isAvc).max(preference)
                        .orElse(atHeight.stream().max(preference).orElseThrow());
                boolean avc = pick.isAvc();
                double fps = atHeight.stream().mapToDouble(Format::fps).max().orElse(0);
                long size = pick.size() > 0 ? pick.size() + (pick.hasAudio() || bestAudio == null || bestAudio.size() < 0
                        ? 0 : bestAudio.size()) : -1;
                String selector = "bv*[height<=" + h + "][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=" + h
                        + "]+ba/b[height<=" + h + "]";
                String label = h + "p" + (fps > 30 ? Math.round(fps) : "") + "  (" + (avc ? "MP4" : "MP4, VP9/AV1") + ")";
                out.add(new Option(label, selector, size, "mp4", false));
            }
            if (out.isEmpty() && formats.stream().anyMatch(Format::hasVideo)) {
                out.add(new Option("Best available", "bv*+ba/b", -1, "mp4", false));
            }
        } else {
            formats.stream().filter(f -> f.hasVideo() && f.hasAudio())
                    .sorted(Comparator.comparingInt(Format::height).thenComparingDouble(Format::tbr).reversed())
                    .forEach(f -> out.add(new Option(
                            (f.height() > 0 ? f.height() + "p" : "Video") + "  (" + f.ext().toUpperCase(Locale.ROOT) + ")",
                            f.id(), f.size(), f.ext(), false)));
            if (out.isEmpty() && !formats.isEmpty()) {
                out.add(new Option("Best available", "b", -1, formats.get(0).ext(), false));
            }
        }
        if (bestAudio != null) {
            String ext = bestAudio.ext().equals("m4a") ? "m4a" : bestAudio.ext();
            out.add(new Option("Audio only  (" + ext.toUpperCase(Locale.ROOT) + ")", "ba[ext=m4a]/ba",
                    bestAudio.size(), ext, true));
        }
        return out;
    }

    private static String str(Object o, String fallback) {
        return o instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static double num(Object o) {
        return o instanceof Double d ? d : 0;
    }
}
