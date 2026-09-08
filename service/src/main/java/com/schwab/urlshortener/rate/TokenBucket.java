package com.schwab.urlshortener.rate;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A single-key token bucket. Refills continuously based on elapsed wall-clock
 * time rather than a background thread, so idle buckets cost nothing between
 * requests.
 */
final class TokenBucket {

    private final long capacity;
    private final double refillTokensPerMillis;
    private final AtomicLong availableTokensMicros; // tokens scaled by 1_000_000 to keep this lock-free with longs
    private volatile long lastRefillMillis;
    private volatile long lastAccessMillis;

    TokenBucket(long capacity, double refillTokensPerSecond, long nowMillis) {
        this.capacity = capacity;
        this.refillTokensPerMillis = refillTokensPerSecond / 1000.0;
        this.availableTokensMicros = new AtomicLong(capacity * 1_000_000L);
        this.lastRefillMillis = nowMillis;
        this.lastAccessMillis = nowMillis;
    }

    /** Attempts to consume one token. Returns true if allowed. */
    synchronized boolean tryConsume(long nowMillis) {
        lastAccessMillis = nowMillis;
        refill(nowMillis);
        long current = availableTokensMicros.get();
        if (current >= 1_000_000L) {
            availableTokensMicros.addAndGet(-1_000_000L);
            return true;
        }
        return false;
    }

    private void refill(long nowMillis) {
        long elapsed = nowMillis - lastRefillMillis;
        if (elapsed <= 0) {
            return;
        }
        long addedMicros = (long) (elapsed * refillTokensPerMillis * 1_000_000L);
        if (addedMicros > 0) {
            long capMicros = capacity * 1_000_000L;
            long updated = Math.min(capMicros, availableTokensMicros.get() + addedMicros);
            availableTokensMicros.set(updated);
            lastRefillMillis = nowMillis;
        }
    }

    long lastAccessMillis() {
        return lastAccessMillis;
    }
}
