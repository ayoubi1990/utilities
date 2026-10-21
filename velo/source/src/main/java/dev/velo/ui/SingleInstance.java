package dev.velo.ui;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Makes sure only one velo runs per user. Two copies would each save their
 * own download list over the same file, and the last one to save wins.
 */
final class SingleInstance {
    private final FileChannel channel;
    private final FileLock lock;

    private SingleInstance(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** Takes the lock for this data folder, or returns null if another velo holds it. */
    static SingleInstance acquire(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        FileChannel ch = FileChannel.open(dataDir.resolve("velo.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        try {
            FileLock lock = ch.tryLock();
            if (lock != null) {
                // Held until the process exits; the OS releases it even after a crash.
                return new SingleInstance(ch, lock);
            }
        } catch (OverlappingFileLockException e) {
            // Already held inside this same JVM.
        }
        ch.close();
        return null;
    }

    void release() throws IOException {
        lock.release();
        channel.close();
    }
}
