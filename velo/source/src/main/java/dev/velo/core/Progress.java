package dev.velo.core;

import java.util.List;

/**
 * @param total       file size in bytes, or -1 when the server did not say
 * @param downloaded  bytes on disk
 * @param speed       smoothed speed in bytes per second
 * @param connections connections currently transferring
 * @param segments    per-range progress; empty for single-stream downloads
 */
public record Progress(long total, long downloaded, double speed, int connections, List<Segment> segments) {
    static Progress initial(long total) {
        return new Progress(total, 0, 0, 0, List.of());
    }
}
