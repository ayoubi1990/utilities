package dev.velo.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks which connection owns which byte range and splits ranges so idle
 * connections can help with the slowest remaining one.
 */
final class SegmentTable {
    private static final class Seg {
        final long start;
        long end;
        long written;
        /** First byte not yet handed to a connection; runs ahead of {@code written} by one unflushed buffer. */
        long reserved;
        boolean owned;

        Seg(long start, long end, long written) {
            this.start = start;
            this.end = end;
            this.written = written;
            this.reserved = written;
        }

        long unreserved() {
            return Math.max(0, end - reserved);
        }

        boolean isDone() {
            return written >= end;
        }
    }

    private final List<Seg> segs = new ArrayList<>();
    private final long minSplit;

    private SegmentTable(long minSplit) {
        this.minSplit = Math.max(1, minSplit);
    }

    static SegmentTable create(long total, int parts, long minSplit) {
        SegmentTable t = new SegmentTable(minSplit);
        long n = Math.min(Math.max(1, parts), Math.max(1, Math.ceilDiv(total, t.minSplit)));
        long size = total / n;
        for (long i = 0; i < n; i++) {
            long start = i * size;
            long end = i == n - 1 ? total : start + size;
            t.segs.add(new Seg(start, end, start));
        }
        return t;
    }

    /** Rebuilds a table from a state file, or returns null if the ranges do not fit {@code total}. */
    static SegmentTable fromSaved(List<Segment> saved, long total, long minSplit) {
        if (saved.isEmpty()) {
            return null;
        }
        SegmentTable t = new SegmentTable(minSplit);
        for (Segment s : saved) {
            if (s.start() < 0 || s.start() > s.written() || s.written() > s.end() || s.end() > total) {
                return null;
            }
            t.segs.add(new Seg(s.start(), s.end(), s.written()));
        }
        return t;
    }

    /**
     * Hands out work for one connection: an unowned range if there is one,
     * otherwise the second half of the largest range still in progress.
     * Returns -1 when nothing is worth taking.
     */
    synchronized int claim() {
        for (int i = 0; i < segs.size(); i++) {
            Seg s = segs.get(i);
            if (!s.owned && s.unreserved() > 0) {
                s.owned = true;
                return i;
            }
        }
        Seg largest = null;
        for (Seg s : segs) {
            if (s.owned && (largest == null || s.unreserved() > largest.unreserved())) {
                largest = s;
            }
        }
        if (largest == null || largest.unreserved() < 2 * minSplit) {
            return -1;
        }
        long mid = largest.reserved + largest.unreserved() / 2;
        Seg tail = new Seg(mid, largest.end, mid);
        tail.owned = true;
        largest.end = mid;
        segs.add(tail);
        return segs.size() - 1;
    }

    /** The byte range {@code [from, to)} a connection should request for segment {@code i}. */
    synchronized long[] pendingRange(int i) {
        Seg s = segs.get(i);
        return new long[] {s.reserved, s.end};
    }

    /**
     * Reserves up to {@code want} bytes of segment {@code i} and returns how many were
     * granted. Fewer than asked (possibly zero) means the segment ended, perhaps
     * because another connection took over its tail.
     */
    synchronized long reserve(int i, long want) {
        Seg s = segs.get(i);
        long n = Math.min(want, s.unreserved());
        s.reserved += n;
        return n;
    }

    /** Records that {@code n} reserved bytes of segment {@code i} are on disk. */
    synchronized void commit(int i, long n) {
        Seg s = segs.get(i);
        s.written += n;
        assert s.written <= s.reserved;
    }

    /** Gives a segment back after a failed connection, dropping reservations that never reached the disk. */
    synchronized void release(int i) {
        Seg s = segs.get(i);
        s.reserved = s.written;
        s.owned = false;
    }

    synchronized long written(int i) {
        return segs.get(i).written;
    }

    synchronized boolean isSegmentDone(int i) {
        return segs.get(i).isDone();
    }

    synchronized boolean isComplete() {
        return segs.stream().allMatch(Seg::isDone);
    }

    synchronized long downloaded() {
        return segs.stream().mapToLong(s -> s.written - s.start).sum();
    }

    synchronized int active() {
        return (int) segs.stream().filter(s -> s.owned && !s.isDone()).count();
    }

    synchronized List<Segment> snapshot() {
        return segs.stream().map(s -> new Segment(s.start, s.end, s.written)).toList();
    }
}
