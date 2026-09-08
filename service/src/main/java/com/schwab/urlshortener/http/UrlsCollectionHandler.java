package com.schwab.urlshortener.http;

import com.schwab.common.json.Json;
import com.schwab.urlshortener.metrics.ServiceMetrics;
import com.schwab.urlshortener.model.ServiceExceptions.InvalidUrlException;
import com.schwab.urlshortener.model.UrlRecord;
import com.schwab.urlshortener.store.UrlStore;
import com.schwab.urlshortener.validation.AliasValidator;
import com.schwab.urlshortener.validation.UrlValidator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles the collection resource: POST /api/v1/urls (create) and
 * GET /api/v1/urls (list most recent, for demo/debugging).
 */
public final class UrlsCollectionHandler implements HttpHandler {

    private final UrlStore store;
    private final ServiceMetrics metrics;
    private final String publicBaseUrl;

    public UrlsCollectionHandler(UrlStore store, ServiceMetrics metrics, String publicBaseUrl) {
        this.store = store;
        this.metrics = metrics;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        boolean error = false;
        try {
            switch (exchange.getRequestMethod()) {
                case "POST" -> handleCreate(exchange);
                case "GET" -> handleList(exchange);
                default -> {
                    error = true;
                    exchange.getResponseHeaders().set("Allow", "GET, POST");
                    HttpUtil.sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Use GET or POST on /api/v1/urls");
                }
            }
        } catch (RuntimeException e) {
            error = true;
            ExceptionMapper.write(exchange, e);
        } finally {
            metrics.recordRequest(System.currentTimeMillis() - start, error);
        }
    }

    private void handleCreate(HttpExchange exchange) throws IOException {
        String body = HttpUtil.readBody(exchange);
        Map<String, Object> request;
        try {
            request = Json.parseObject(body);
        } catch (RuntimeException e) {
            throw new InvalidUrlException("Request body must be a JSON object: " + e.getMessage());
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
        HttpUtil.sendJson(exchange, 201, toResponseBody(record));
    }

    private void handleList(HttpExchange exchange) throws IOException {
        Map<String, String> query = HttpUtil.parseQuery(exchange);
        int limit = 20;
        if (query.containsKey("limit")) {
            try {
                limit = Math.max(1, Math.min(100, Integer.parseInt(query.get("limit"))));
            } catch (NumberFormatException ignored) {
                // fall back to default
            }
        }
        List<UrlRecord> records = store.recent(limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", records.size());
        body.put("items", records.stream().map(this::toResponseBody).toList());
        HttpUtil.sendJson(exchange, 200, body);
    }

    private Map<String, Object> toResponseBody(UrlRecord record) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", record.code());
        m.put("shortUrl", publicBaseUrl + "/" + record.code());
        m.put("longUrl", record.longUrl());
        m.put("createdAt", record.createdAtMillis());
        m.put("expiresAt", record.expiresAtMillis());
        m.put("customAlias", record.customAlias());
        m.put("active", record.active());
        m.put("clickCount", record.clickCount());
        return m;
    }
}
