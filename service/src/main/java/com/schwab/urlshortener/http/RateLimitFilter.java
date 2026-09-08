package com.schwab.urlshortener.http;

import com.schwab.urlshortener.metrics.ServiceMetrics;
import com.schwab.urlshortener.rate.RateLimiter;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/** Applies per-client rate limiting ahead of every handler. */
public final class RateLimitFilter extends Filter {

    private final RateLimiter rateLimiter;
    private final ServiceMetrics metrics;

    public RateLimitFilter(RateLimiter rateLimiter, ServiceMetrics metrics) {
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    @Override
    public String description() {
        return "Per-client token-bucket rate limiter";
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        String key = HttpUtil.clientKey(exchange);
        if (rateLimiter.tryConsume(key)) {
            chain.doFilter(exchange);
        } else {
            metrics.recordRateLimited();
            exchange.getResponseHeaders().set("Retry-After", "1");
            HttpUtil.sendError(exchange, 429, "RATE_LIMITED", "Too many requests from this client, try again shortly");
        }
    }
}
