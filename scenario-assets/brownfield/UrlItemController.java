package com.schwab.urlshortener.web;

import com.schwab.urlshortener.model.ClickEvent;
import com.schwab.urlshortener.model.ServiceExceptions.UrlNotFoundException;
import com.schwab.urlshortener.model.UrlRecord;
import com.schwab.urlshortener.store.UrlStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles the /api/v1/urls/{code} and /api/v1/urls/{code}/analytics resources
 * (GET info, GET analytics, DELETE), plus GET /api/v1/urls/expired.
 *
 * <p>Brownfield addition: GET /api/v1/urls/expired lists short codes that are
 * past their TTL but not yet soft-deleted, so an operator (or a scheduled
 * cleanup job) doesn't have to scan every record by hand.
 *
 * <p>Unlike the original zero-dependency version -- where this had to be
 * handled as a branch inside the {@code {code}} handler to avoid a raw
 * string-prefix routing collision in {@code com.sun.net.httpserver} -- this
 * can simply be its own {@code @GetMapping("/api/v1/urls/expired")}: Spring
 * MVC always prefers the more specific literal mapping over the
 * {@code {code}} path-variable mapping for an exact path match, so the two
 * routes never collide in the first place. {@link com.schwab.urlshortener.validation.AliasValidator}
 * still reserves the exact word "expired" so a record can never be created
 * that would be unreachable via GET /api/v1/urls/{code} (shadowed by this
 * more specific mapping) -- see its javadoc and docs/testing-and-limitations.md.
 */
@RestController
public class UrlItemController {

    private static final int EXPIRED_LIST_LIMIT = 1000;

    private final UrlStore store;

    public UrlItemController(UrlStore store) {
        this.store = store;
    }

    @GetMapping("/api/v1/urls/expired")
    public Map<String, Object> listExpired() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> expired = new ArrayList<>();
        for (UrlRecord record : store.recent(EXPIRED_LIST_LIMIT)) {
            if (record.active() && record.isExpired(now)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", record.code());
                m.put("longUrl", record.longUrl());
                m.put("expiresAt", record.expiresAtMillis());
                expired.add(m);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", expired.size());
        body.put("items", expired);
        return body;
    }

    @GetMapping("/api/v1/urls/{code}")
    public Map<String, Object> info(@PathVariable String code, HttpServletRequest httpRequest) {
        UrlRecord record = find(code);
        return UrlsController.toResponseBody(record, httpRequest);
    }

    @GetMapping("/api/v1/urls/{code}/analytics")
    public Map<String, Object> analytics(@PathVariable String code) {
        UrlRecord record = find(code);
        List<ClickEvent> recent = store.recentClicks(code, 20);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("totalClicks", record.clickCount());
        body.put("recentClicks", recent.stream().map(UrlItemController::toClickBody).toList());
        return body;
    }

    @DeleteMapping("/api/v1/urls/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        boolean removed = store.softDelete(code);
        if (!removed) {
            throw new UrlNotFoundException("No such short code: " + code);
        }
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    private UrlRecord find(String code) {
        return store.find(code).orElseThrow(() -> new UrlNotFoundException("No such short code: " + code));
    }

    private static Map<String, Object> toClickBody(ClickEvent event) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", event.timestampMillis());
        m.put("referrer", event.referrer());
        m.put("userAgent", event.userAgent());
        m.put("clientHash", event.clientHash());
        return m;
    }
}
