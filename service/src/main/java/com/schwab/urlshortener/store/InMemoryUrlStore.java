package com.schwab.urlshortener.store;

import com.schwab.urlshortener.model.ClickEvent;
import com.schwab.urlshortener.model.ServiceExceptions.AliasConflictException;
import com.schwab.urlshortener.model.UrlRecord;
import com.schwab.urlshortener.util.Base62Encoder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory {@link UrlStore} backed by a {@link WriteAheadLog} for crash
 * recovery. Suitable for a single-instance prototype; docs/architecture.md
 * describes how this would be swapped for a shared database + cache in a
 * multi-instance deployment without changing the {@link UrlStore} contract.
 */
public final class InMemoryUrlStore implements UrlStore {

    private static final int MAX_RECENT_CLICKS_PER_CODE = 50;
    private static final int MAX_GENERATION_ATTEMPTS = 1000;

    private final ConcurrentHashMap<String, UrlRecord> byCode = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<ClickEvent>> clicksByCode = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(0);
    private final WriteAheadLog wal;

    public InMemoryUrlStore(WriteAheadLog wal) {
        this.wal = wal;
        replay();
    }

    private void replay() {
        long maxSeenId = 0;
        for (Map<String, Object> event : wal.replay()) {
            String type = (String) event.get("type");
            switch (type) {
                case "CREATE" -> {
                    String code = (String) event.get("code");
                    String longUrl = (String) event.get("longUrl");
                    long createdAt = toLong(event.get("createdAt"));
                    Long expiresAt = event.get("expiresAt") == null ? null : toLong(event.get("expiresAt"));
                    boolean customAlias = Boolean.TRUE.equals(event.get("customAlias"));
                    UrlRecord record = new UrlRecord(code, longUrl, createdAt, expiresAt, customAlias);
                    byCode.put(code, record);
                    if (!customAlias) {
                        Long seqId = event.get("seqId") == null ? null : toLong(event.get("seqId"));
                        if (seqId != null) {
                            maxSeenId = Math.max(maxSeenId, seqId);
                        }
                    }
                }
                case "DELETE" -> {
                    UrlRecord r = byCode.get((String) event.get("code"));
                    if (r != null) {
                        r.deactivate();
                    }
                }
                case "CLICK" -> {
                    String code = (String) event.get("code");
                    UrlRecord r = byCode.get(code);
                    if (r != null) {
                        r.incrementClicks();
                        ClickEvent ce = new ClickEvent(code, toLong(event.get("timestamp")),
                                (String) event.get("referrer"), (String) event.get("userAgent"),
                                (String) event.get("clientHash"));
                        trackRecentClick(ce);
                    }
                }
                default -> { /* ignore unknown event types for forward-compat */ }
            }
        }
        idSequence.set(maxSeenId);
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(o));
    }

    @Override
    public UrlRecord create(String longUrl, String customAlias, Long ttlSeconds) {
        long now = System.currentTimeMillis();
        Long expiresAt = ttlSeconds == null ? null : now + (ttlSeconds * 1000);

        if (customAlias != null) {
            return createWithCode(customAlias, longUrl, now, expiresAt, true, null);
        }
        return createWithGeneratedCode(longUrl, now, expiresAt);
    }

    private UrlRecord createWithGeneratedCode(String longUrl, long now, Long expiresAt) {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            long id = idSequence.incrementAndGet();
            String candidate = Base62Encoder.encode(id);
            if (!byCode.containsKey(candidate)) {
                try {
                    return createWithCode(candidate, longUrl, now, expiresAt, false, id);
                } catch (AliasConflictException raceLost) {
                    // Another thread won the race for this exact code; try the next id.
                }
            }
        }
        throw new IllegalStateException("Could not generate a unique short code after "
                + MAX_GENERATION_ATTEMPTS + " attempts");
    }

    private UrlRecord createWithCode(String code, String longUrl, long now, Long expiresAt,
                                      boolean customAlias, Long seqId) {
        UrlRecord record = new UrlRecord(code, longUrl, now, expiresAt, customAlias);
        UrlRecord existing = byCode.putIfAbsent(code, record);
        if (existing != null) {
            throw new AliasConflictException("Short code already in use: " + code);
        }
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("type", "CREATE");
        event.put("code", code);
        event.put("longUrl", longUrl);
        event.put("createdAt", now);
        event.put("expiresAt", expiresAt);
        event.put("customAlias", customAlias);
        if (seqId != null) {
            event.put("seqId", seqId);
        }
        wal.append(event);
        return record;
    }

    @Override
    public Optional<UrlRecord> find(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    @Override
    public boolean softDelete(String code) {
        UrlRecord record = byCode.get(code);
        if (record == null) {
            return false;
        }
        record.deactivate();
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("type", "DELETE");
        event.put("code", code);
        wal.append(event);
        return true;
    }

    @Override
    public void recordClick(ClickEvent event) {
        UrlRecord record = byCode.get(event.code());
        if (record == null) {
            return;
        }
        record.incrementClicks();
        trackRecentClick(event);
        Map<String, Object> walEvent = new java.util.LinkedHashMap<>();
        walEvent.put("type", "CLICK");
        walEvent.put("code", event.code());
        walEvent.put("timestamp", event.timestampMillis());
        walEvent.put("referrer", event.referrer());
        walEvent.put("userAgent", event.userAgent());
        walEvent.put("clientHash", event.clientHash());
        wal.append(walEvent);
    }

    private void trackRecentClick(ClickEvent event) {
        Deque<ClickEvent> deque = clicksByCode.computeIfAbsent(event.code(), k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addFirst(event);
            while (deque.size() > MAX_RECENT_CLICKS_PER_CODE) {
                deque.removeLast();
            }
        }
    }

    @Override
    public List<ClickEvent> recentClicks(String code, int limit) {
        Deque<ClickEvent> deque = clicksByCode.get(code);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            List<ClickEvent> result = new ArrayList<>();
            int i = 0;
            for (ClickEvent e : deque) {
                if (i++ >= limit) {
                    break;
                }
                result.add(e);
            }
            return result;
        }
    }

    @Override
    public int size() {
        return byCode.size();
    }

    @Override
    public List<UrlRecord> recent(int limit) {
        return byCode.values().stream()
                .sorted((a, b) -> Long.compare(b.createdAtMillis(), a.createdAtMillis()))
                .limit(limit)
                .toList();
    }
}
