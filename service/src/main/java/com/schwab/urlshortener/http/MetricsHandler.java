package com.schwab.urlshortener.http;

import com.schwab.urlshortener.metrics.ServiceMetrics;
import com.schwab.urlshortener.store.UrlStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/** Reliability metrics endpoint at GET /metrics (JSON, not Prometheus text format -- see architecture.md). */
public final class MetricsHandler implements HttpHandler {

    private final ServiceMetrics metrics;
    private final UrlStore store;

    public MetricsHandler(ServiceMetrics metrics, UrlStore store) {
        this.metrics = metrics;
        this.store = store;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // See HealthHandler for why this exact-path check exists (com.sun.net.httpserver
        // matches contexts by string prefix, not path segment).
        if (!"/metrics".equals(exchange.getRequestURI().getPath())) {
            HttpUtil.sendError(exchange, 404, "NOT_FOUND", "Unknown path");
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            HttpUtil.sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Use GET");
            return;
        }
        HttpUtil.sendJson(exchange, 200, metrics.snapshot(store.size()));
    }
}
