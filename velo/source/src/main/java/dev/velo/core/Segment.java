package dev.velo.core;

/** A byte range {@code [start, end)} of the file; {@code written} is the first byte not yet on disk. */
public record Segment(long start, long end, long written) {
    public boolean isDone() {
        return written >= end;
    }
}
