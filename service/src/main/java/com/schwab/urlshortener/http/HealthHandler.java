package com.schwab.urlshortener.http;

import com.schwab.urlshortener.store.UrlStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Liveness/readiness endpoint at GET /healthz. */
public final class HealthHandler implements HttpHandler {

    private final UrlStore store;

    public HealthHandler(UrlStore store) {
        this.store = store;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Defense in depth: com.sun.net.httpserver matches contexts by string prefix, so a request
        // for e.g. "/healthz-legacy" would also land here. AliasValidator already stops such a code
        // from being created, but we still refuse to serve health data for a non-exact path rather
        // than relying solely on that upstream guard.
        if (!"/healthz".equals(exchange.getRequestURI().getPath())) {
            HttpUtil.sendError(exchange, 404, "NOT_FOUND", "Unknown path");
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            HttpUtil.sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Use GET");
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("storedUrlCount", store.size());
        HttpUtil.sendJson(exchange, 200, body);
    }
}
