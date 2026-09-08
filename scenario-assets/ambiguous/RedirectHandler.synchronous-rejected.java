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

/**
 * REJECTED INTERPRETATION (kept only as a scenario asset -- see
 * docs/scenarios/03-ambiguous.md): "make analytics more reliable" read as
 * "never lose a click, even if it means the redirect waits for the write."
 * Analytics is now recorded synchronously, before the redirect is sent.
 *
 * <p>This is a real, compilable interpretation, not a strawman -- and it is
 * exactly the trade-off the orchestrator's clarification approval gate
 * exists to catch before it ships: it silently reverses the documented
 * design decision that the redirect path must never depend on the
 * analytics write (see the original class javadoc), coupling the
 * service's most latency-sensitive endpoint to whatever the store's write
 * path costs. The scenario's post-write self-check fails specifically
 * because of that reversal, and the paired rollback agent restores the
 * previous (async) version of this file.
 */
public final class RedirectHandler implements HttpHandler {

    private final UrlStore store;
    private final ServiceMetrics metrics;
    private final ExecutorService analyticsExecutor; // no longer used for clicks in this interpretation

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

            String referrer = exchange.getRequestHeaders().getFirst("Referer");
            String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
            String clientHash = HttpUtil.hashClient(HttpUtil.clientKey(exchange));

            // Regression: the redirect now waits on the analytics write before responding.
            store.recordClick(new ClickEvent(code, now, referrer, userAgent, clientHash));

            exchange.getResponseHeaders().set("Location", record.longUrl());
            HttpUtil.sendNoBody(exchange, 302);
            metrics.recordRedirect();
        } catch (RuntimeException e) {
            error = true;
            ExceptionMapper.write(exchange, e);
        } finally {
            metrics.recordRequest(System.currentTimeMillis() - start, error);
        }
    }
}
