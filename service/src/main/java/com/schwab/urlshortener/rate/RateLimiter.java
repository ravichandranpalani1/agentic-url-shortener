package com.schwab.urlshortener.rate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-key (typically per-client-IP) rate limiting via token buckets.
 *
 * <p>Trade-off: buckets are kept in an in-process map, which is appropriate
 * for a single-instance prototype but does not enforce a global limit across
 * multiple instances. A production, horizontally-scaled deployment would
 * back this with a shared store (e.g. Redis + Lua script) instead --
 * documented in docs/architecture.md under "Scaling beyond one instance".
 * Idle buckets are swept periodically so this map does not grow unbounded
 * under a churn of distinct client keys.
 */
public final class RateLimiter {

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final long capacity;
    private final double refillPerSecond;
    private final long idleEvictMillis;
    private final AtomicLong lastSweepMillis = new AtomicLong(0);
    private static final long SWEEP_INTERVAL_MILLIS = 60_000;

    public RateLimiter(long capacity, double refillPerSecond, long idleEvictMillis) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.idleEvictMillis = idleEvictMillis;
    }

    public boolean tryConsume(String key) {
        long now = System.currentTimeMillis();
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(capacity, refillPerSecond, now));
        boolean allowed = bucket.tryConsume(now);
        maybeSweep(now);
        return allowed;
    }

    public int trackedKeyCount() {
        return buckets.size();
    }

    private void maybeSweep(long now) {
        long last = lastSweepMillis.get();
        if (now - last < SWEEP_INTERVAL_MILLIS) {
            return;
        }
        if (!lastSweepMillis.compareAndSet(last, now)) {
            return; // another thread is already sweeping
        }
        buckets.entrySet().removeIf(e -> now - e.getValue().lastAccessMillis() > idleEvictMillis);
    }
}
