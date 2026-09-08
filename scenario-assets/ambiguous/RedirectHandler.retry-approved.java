package com.schwab.urlshortener.http;

import com.schwab.urlshortener.metrics.ServiceMetrics;
import com.schwab.urlshortener.model.ClickEvent;
import com.schwab.urlshortener.model.ServiceExceptions.UrlGoneException;
import com.schwab.urlshortener.model.ServiceExceptions.UrlNotFoundException;
import com.schwab.urlshortener.model.UrlRecord;
import com.schwab.urlshortener.store.UrlStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Handles GET /{code}: the actual redirect. Registered on the root context
 * "/", which com.sun.net.httpserver matches only when no more specific
 * context (e.g. /api/v1/urls, /healthz, /metrics) applies.
 *
 * <p>Click analytics are recorded on a background executor so a slow or
 * blocked analytics write can never add latency to the redirect itself --
 * the single most latency-sensitive path in the service.
 *
 * <p>APPROVED interpretation of "make analytics more reliable" (see
 * docs/scenarios/03-ambiguous.md): the analytics submission itself is now
 * defended against {@link RejectedExecutionException} -- if the analytics
 * executor is ever saturated or shutting down, the click is logged as
 * dropped instead of the exception propagating out of the redirect
 * handler. This keeps the original async-by-design guarantee (redirect
 * latency never depends on the analytics write) while closing the one gap
 * in it: an executor-level rejection used to be able to surface as an
 * uncaught exception on the request thread.
 */
public final class RedirectHandler implements HttpHandler {

    private final UrlStore store;
    private final ServiceMetrics metrics;
    private final ExecutorService analyticsExecutor;

    public RedirectHandler(UrlStore store, ServiceMetrics metrics, ExecutorService analyticsExecutor) {
        this.store = store;
        this.metrics = metrics;
        this.analyticsExecutor = analyticsExecutor;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        long start = System.currentTimeMillis();
        boolean error = false;
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                error = true;
                exchange.getResponseHeaders().set("Allow", "GET");
                HttpUtil.sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Use GET");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.isEmpty()) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("service", "agentic-url-shortener");
                body.put("status", "ok");
                HttpUtil.sendJson(exchange, 200, body);
                return;
            }

            String code = path.substring(1);
            if (code.contains("/")) {
                error = true;
                HttpUtil.sendError(exchange, 404, "NOT_FOUND", "Unknown path");
                return;
            }

            UrlRecord record = store.find(code).orElseThrow(() -> new UrlNotFoundException("No such short code: " + code));
            long now = System.currentTimeMillis();
            if (!record.active()) {
                throw new UrlGoneException("Short code has been deleted: " + code);
            }
            if (record.isExpired(now)) {
                throw new UrlGoneException("Short code has expired: " + code);
            }

            exchange.getResponseHeaders().set("Location", record.longUrl());
            HttpUtil.sendNoBody(exchange, 302);
            metrics.recordRedirect();

            String referrer = exchange.getRequestHeaders().getFirst("Referer");
            String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
            String clientHash = HttpUtil.hashClient(HttpUtil.clientKey(exchange));
            try {
                analyticsExecutor.submit(() -> {
                    try {
                        store.recordClick(new ClickEvent(code, now, referrer, userAgent, clientHash));
                    } catch (RuntimeException e) {
                        // Analytics is best-effort: a failure here must never surface to the client,
                        // who has already received their redirect.
                        System.err.println("Failed to record click for " + code + ": " + e.getMessage());
                    }
                });
            } catch (RejectedExecutionException e) {
                // The analytics executor is saturated or shutting down. Degrade to a logged drop
                // rather than letting the rejection propagate out of an already-completed redirect.
                System.err.println("Analytics executor rejected click for " + code + "; click dropped: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            error = true;
            ExceptionMapper.write(exchange, e);
        } finally {
            metrics.recordRequest(System.currentTimeMillis() - start, error);
        }
    }
}
