package com.schwab.urlshortener.store;

import com.schwab.urlshortener.model.ClickEvent;
import com.schwab.urlshortener.model.UrlRecord;

import java.util.List;
import java.util.Optional;

public interface UrlStore {

    /**
     * Creates a new short URL. If {@code customAlias} is null, a code is generated from an
     * internal sequence. Throws AliasConflictException if the resulting code already exists.
     */
    UrlRecord create(String longUrl, String customAlias, Long ttlSeconds);

    Optional<UrlRecord> find(String code);

    /** Soft-deletes a record (marks inactive). Returns false if the code was not found. */
    boolean softDelete(String code);

    void recordClick(ClickEvent event);

    List<ClickEvent> recentClicks(String code, int limit);

    int size();

    List<UrlRecord> recent(int limit);
}
