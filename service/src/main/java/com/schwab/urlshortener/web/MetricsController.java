package com.schwab.urlshortener.web;

import com.schwab.urlshortener.metrics.ServiceMetrics;
import com.schwab.urlshortener.store.UrlStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Reliability metrics endpoint at GET /metrics (JSON, not Prometheus text format -- see architecture.md). */
@RestController
public class MetricsController {

    private final ServiceMetrics metrics;
    private final UrlStore store;

    public MetricsController(ServiceMetrics metrics, UrlStore store) {
        this.metrics = metrics;
        this.store = store;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return metrics.snapshot(store.size());
    }
}
