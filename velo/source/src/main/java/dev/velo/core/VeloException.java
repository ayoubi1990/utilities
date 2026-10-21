package dev.velo.core;

import java.io.IOException;

/** Every failure the engine reports, tagged with a {@link Kind} callers can branch on. */
public final class VeloException extends Exception {
    public enum Kind { INVALID_URL, NETWORK, IO, STATUS, RANGE_NOT_HONORED, INCOMPLETE, CANCELLED }

    private final Kind kind;
    private final int status;

    private VeloException(Kind kind, String message, int status, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = status;
    }

    public Kind kind() {
        return kind;
    }

    /** The HTTP status for {@link Kind#STATUS}, otherwise 0. */
    public int status() {
        return status;
    }

    static VeloException invalidUrl(String url) {
        return new VeloException(Kind.INVALID_URL, "invalid URL: " + url, 0, null);
    }

    static VeloException network(Exception cause) {
        return new VeloException(Kind.NETWORK, "network error: " + cause.getMessage(), 0, cause);
    }

    static VeloException io(IOException cause) {
        return new VeloException(Kind.IO, "I/O error: " + cause.getMessage(), 0, cause);
    }

    static VeloException status(int status) {
        return new VeloException(Kind.STATUS, "server returned HTTP " + status, status, null);
    }

    static VeloException rangeNotHonored() {
        return new VeloException(Kind.RANGE_NOT_HONORED, "server ignored the byte-range request", 0, null);
    }

    static VeloException incomplete() {
        return new VeloException(Kind.INCOMPLETE, "connection closed before the data was complete", 0, null);
    }

    static VeloException cancelled() {
        return new VeloException(Kind.CANCELLED, "download cancelled", 0, null);
    }
}
