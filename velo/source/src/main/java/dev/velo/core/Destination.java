package dev.velo.core;

import java.nio.file.Path;

/** Where a download is saved. */
public sealed interface Destination {
    /** Save into this folder under the server-supplied name, adding " (1)", " (2)"… if it is taken. */
    record Dir(Path dir) implements Destination {}

    /** Save to exactly this path, replacing any existing file. */
    record File(Path file) implements Destination {}

    static Destination dir(Path dir) {
        return new Dir(dir);
    }

    static Destination file(Path file) {
        return new File(file);
    }
}
