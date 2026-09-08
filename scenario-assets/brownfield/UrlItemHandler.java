package com.schwab.urlshortener.http;

import com.schwab.urlshortener.metrics.ServiceMetrics;
import com.schwab.urlshortener.model.ClickEvent;
import com.schwab.urlshortener.model.ServiceExceptions.UrlNotFoundException;
import com.schwab.urlshortener.model.UrlRecord;
import com.schwab.urlshortener.store.UrlStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles the /api/v1/urls/{code} and /api/v1/urls/{code}/analytics resources:
 * GET (info), GET .../analytics, DELETE (soft delete).
 *
 * <p>Brownfield addition: GET /api/v1/urls/expired lists short codes that
 * are past their TTL but not yet soft-deleted, so an operator (or a
 * scheduled cleanup job) doesn't have to scan every record by hand. It's
 * handled directly in this class -- rather than as a separate HttpServer
 * context -- specifically to avoid re-creating the exact routing-collision
 * bug fixed in AliasValidator: a second, more specific context registered
 * at "/api/v1/urls/expired" would silently shadow a real short code
 * literally named "expired", the same class of bug this handler's
 * "analytics" branch already had to be careful about. "expired" is also
 * added to AliasValidator's reserved words for the same reason, so that
 * bug class cannot resurface here either. Uses only the existing UrlStore
 * public API (recent(), UrlRecord.isExpired()), so no store-layer change
 * was needed.
 */
public final class UrlItemHandler implements HttpHandler {

    private static final String PREFIX = "/api/v1/urls/";
    private static final int EXPIRED_LIST_LIMIT = 1000;

    private final UrlStore store;
    private final ServiceMetrics metrics;
    private final String publicBaseUrl;

    public UrlItemHandler(UrlStore store, ServiceMetrics metrics, String publicBaseUrl) {
        this.store = store;
        this.metrics = metrics;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        boolean error = false;
        try {
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith(PREFIX) || path.length() <= PREFIX.length()) {
                error = true;
                HttpUtil.sendError(exchange, 404, "NOT_FOUND", "Missing short code in path");
                return;
            }
            String remainder = path.substring(PREFIX.length());
            String[] parts = remainder.split("/");
            String code = parts[0];
            boolean analytics = parts.length > 1 && parts[1].equals("analytics");

            if (code.equals("expired") && parts.length == 1) {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    error = true;
                    exchange.getResponseHeaders().set("Allow", "GET");
                    HttpUtil.sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Use GET");
                    return;
                }
                handleExpiredList(exchange);
                return;
            }

            switch (exchange.getRequestMethod()) {
                case "GET" -> {
                    if (analytics) {
                        handleAnalytics(exchange, code);
                    } else {
                        handleInfo(exchange, code);
                    }
                }
                case "DELETE" -> handleDelete(exchange, code);
                default -> {
                    error = true;
                    exchange.getResponseHeaders().set("Allow", "GET, DELETE");
                    HttpUtil.sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Use GET or DELETE");
                }
            }
        } catch (RuntimeException e) {
            error = true;
            ExceptionMapper.write(exchange, e);
        } finally {
            metrics.recordRequest(System.currentTimeMillis() - start, error);
        }
    }

    private void handleExpiredList(HttpExchange exchange) throws IOException {
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
        HttpUtil.sendJson(exchange, 200, body);
    }

    private void handleInfo(HttpExchange exchange, String code) throws IOException {
        UrlRecord record = store.find(code).orElseThrow(() -> new UrlNotFoundException("No such short code: " + code));
        HttpUtil.sendJson(exchange, 200, toInfoBody(record));
    }

    private void handleAnalytics(HttpExchange exchange, String code) throws IOException {
        UrlRecord record = store.find(code).orElseThrow(() -> new UrlNotFoundException("No such short code: " + code));
        List<ClickEvent> recent = store.recentClicks(code, 20);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("totalClicks", record.clickCount());
        body.put("recentClicks", recent.stream().map(this::toClickBody).toList());
        HttpUtil.sendJson(exchange, 200, body);
    }

    private void handleDelete(HttpExchange exchange, String code) throws IOException {
        boolean removed = store.softDelete(code);
        if (!removed) {
            throw new UrlNotFoundException("No such short code: " + code);
        }
        HttpUtil.sendNoBody(exchange, 204);
    }

    private Map<String, Object> toInfoBody(UrlRecord record) {
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

    private Map<String, Object> toClickBody(ClickEvent event) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", event.timestampMillis());
        m.put("referrer", event.referrer());
        m.put("userAgent", event.userAgent());
        m.put("clientHash", event.clientHash());
        return m;
    }
}
