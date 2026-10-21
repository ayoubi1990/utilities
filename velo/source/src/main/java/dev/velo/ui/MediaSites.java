package dev.velo.ui;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Recognises addresses of video pages that should go to yt-dlp rather than be fetched as files. */
final class MediaSites {
    private static final Set<String> HOSTS = Set.of(
            "youtube.com", "youtu.be", "youtube-nocookie.com", "vimeo.com", "dailymotion.com", "dai.ly",
            "twitch.tv", "facebook.com", "fb.watch", "instagram.com", "x.com", "twitter.com", "tiktok.com",
            "reddit.com", "v.redd.it", "soundcloud.com", "bilibili.com", "rumble.com", "streamable.com",
            "ted.com", "bandcamp.com", "archive.org");

    private MediaSites() {}

    static boolean isVideoPage(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(Locale.ROOT);
            for (String h : HOSTS) {
                if (host.equals(h) || host.endsWith("." + h)) {
                    return true;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Not a valid address; treat it as a file.
        }
        return false;
    }
}
