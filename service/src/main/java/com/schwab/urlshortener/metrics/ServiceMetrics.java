package com.schwab.urlshortener.metrics;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process reliability counters exposed at /metrics. This is intentionally
 * simple (no histograms/percentiles) -- a real deployment would ship these
 * to a metrics backend, or use Spring Boot Actuator + Micrometer directly;
 * see docs/architecture.md.
 */
@Component
public final class ServiceMetrics {

    private final long startTimeMillis = System.currentTimeMillis();
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();
    private final AtomicLong rateLimited = new AtomicLong();
    private final AtomicLong redirects = new AtomicLong();
    private final AtomicLong latencySumMillis = new AtomicLong();

    public void recordRequest(long latencyMillis, boolean error) {
        totalRequests.incrementAndGet();
        latencySumMillis.addAndGet(latencyMillis);
        if (error) {
            totalErrors.incrementAndGet();
        }
    }

    public void recordRedirect() {
        redirects.incrementAndGet();
    }

    public void recordRateLimited() {
        rateLimited.incrementAndGet();
    }

    public Map<String, Object> snapshot(int storeSize) {
        long requests = totalRequests.get();
        double avgLatency = requests == 0 ? 0.0 : (double) latencySumMillis.get() / requests;
        double errorRate = requests == 0 ? 0.0 : (double) totalErrors.get() / requests;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uptimeSeconds", (System.currentTimeMillis() - startTimeMillis) / 1000);
        m.put("totalRequests", requests);
        m.put("totalErrors", totalErrors.get());
        m.put("errorRate", round(errorRate));
        m.put("rateLimitedRequests", rateLimited.get());
        m.put("totalRedirects", redirects.get());
        m.put("avgLatencyMillis", round(avgLatency));
        m.put("storedUrlCount", storeSize);
        return m;
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
