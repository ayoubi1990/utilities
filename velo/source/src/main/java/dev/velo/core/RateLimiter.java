package dev.velo.core;

/**
 * A token bucket shared by every connection of every download, so the limit
 * applies to the total speed. A rate of 0 means unlimited.
 */
public final class RateLimiter {
    private long rate;
    private double tokens;
    private long last = System.nanoTime();

    public RateLimiter(long bytesPerSecond) {
        this.rate = bytesPerSecond;
        this.tokens = bytesPerSecond;
    }

    public synchronized long rate() {
        return rate;
    }

    /** Changes the limit while downloads are running. */
    public synchronized void setRate(long bytesPerSecond) {
        rate = bytesPerSecond;
        tokens = Math.min(tokens, bytesPerSecond);
        last = System.nanoTime();
    }

    /** Takes {@code n} bytes from the bucket, sleeping until the debt is paid off. */
    public void acquire(long n) throws InterruptedException {
        long waitNanos;
        synchronized (this) {
            if (rate == 0) {
                return;
            }
            long now = System.nanoTime();
            // Allow at most one second of burst.
            tokens = Math.min(rate, tokens + (now - last) / 1e9 * rate);
            last = now;
            tokens -= n;
            if (tokens >= 0) {
                return;
            }
            waitNanos = (long) (-tokens / rate * 1e9);
        }
        Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
    }
}
