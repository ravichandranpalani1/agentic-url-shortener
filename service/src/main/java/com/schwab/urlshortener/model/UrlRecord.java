package com.schwab.urlshortener.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A single short-code -> long-URL mapping. Mutable fields are volatile /
 * atomic so the record can be safely shared across the server's worker
 * threads without external locking.
 */
public final class UrlRecord {

    private final String code;
    private final String longUrl;
    private final long createdAtMillis;
    private final boolean customAlias;
    private volatile Long expiresAtMillis; // null == never expires
    private volatile boolean active = true;
    private final AtomicLong clickCount = new AtomicLong();

    public UrlRecord(String code, String longUrl, long createdAtMillis, Long expiresAtMillis, boolean customAlias) {
        this.code = code;
        this.longUrl = longUrl;
        this.createdAtMillis = createdAtMillis;
        this.expiresAtMillis = expiresAtMillis;
        this.customAlias = customAlias;
    }

    public String code() {
        return code;
    }

    public String longUrl() {
        return longUrl;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public Long expiresAtMillis() {
        return expiresAtMillis;
    }

    public boolean customAlias() {
        return customAlias;
    }

    public boolean active() {
        return active;
    }

    public void deactivate() {
        this.active = false;
    }

    public long clickCount() {
        return clickCount.get();
    }

    public long incrementClicks() {
        return clickCount.incrementAndGet();
    }

    /** Sets the click counter directly; used only when replaying the write-ahead log at startup. */
    public void setClickCountForReplay(long value) {
        clickCount.set(value);
    }

    public boolean isExpired(long nowMillis) {
        return expiresAtMillis != null && nowMillis >= expiresAtMillis;
    }

    /** A record is servable (redirectable) only while active and not expired. */
    public boolean isLive(long nowMillis) {
        return active && !isExpired(nowMillis);
    }
}
