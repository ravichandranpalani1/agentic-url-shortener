package com.schwab.urlshortener.web;

import com.schwab.urlshortener.model.ServiceExceptions.InvalidUrlException;
import com.schwab.urlshortener.model.UrlRecord;
import com.schwab.urlshortener.store.UrlStore;
import com.schwab.urlshortener.validation.AliasValidator;
import com.schwab.urlshortener.validation.UrlValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles the collection resource: POST /api/v1/urls (create) and
 * GET /api/v1/urls (list most recent, for demo/debugging).
 */
@RestController
public class UrlsController {

    private final UrlStore store;

    public UrlsController(UrlStore store) {
        this.store = store;
    }

    @PostMapping("/api/v1/urls")
    public ResponseEntity<Object> create(@RequestBody(required = false) Map<String, Object> request,
                                          HttpServletRequest httpRequest) {
        if (request == null) {
            throw new InvalidUrlException("Request body must be a JSON object");
        }
        Object longUrlObj = request.get("longUrl");
        if (!(longUrlObj instanceof String longUrl)) {
            throw new InvalidUrlException("Request body must include a string field 'longUrl'");
        }
        String customAlias = request.get("customAlias") instanceof String s ? s : null;
        Long ttlSeconds = null;
        if (request.get("ttlSeconds") instanceof Number n) {
            ttlSeconds = n.longValue();
            if (ttlSeconds <= 0) {
                throw new InvalidUrlException("ttlSeconds must be positive if provided");
            }
        }

        UrlValidator.validate(longUrl);
        AliasValidator.validate(customAlias);

        UrlRecord record = store.create(longUrl, customAlias, ttlSeconds);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponseBody(record, httpRequest));
    }

    @GetMapping("/api/v1/urls")
    public Map<String, Object> list(@RequestParam(name = "limit", required = false) Integer limitParam,
                                     HttpServletRequest httpRequest) {
        int limit = limitParam == null ? 20 : Math.max(1, Math.min(100, limitParam));
        List<UrlRecord> records = store.recent(limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", records.size());
        body.put("items", records.stream().map(r -> toResponseBody(r, httpRequest)).toList());
        return body;
    }

    static Map<String, Object> toResponseBody(UrlRecord record, HttpServletRequest httpRequest) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", record.code());
        m.put("shortUrl", BaseUrl.of(httpRequest) + "/" + record.code());
        m.put("longUrl", record.longUrl());
        m.put("createdAt", record.createdAtMillis());
        m.put("expiresAt", record.expiresAtMillis());
        m.put("customAlias", record.customAlias());
        m.put("active", record.active());
        m.put("clickCount", record.clickCount());
        return m;
    }
}
