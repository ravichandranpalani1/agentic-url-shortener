package com.schwab.urlshortener.web;

import com.schwab.urlshortener.store.UrlStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Liveness/readiness endpoint at GET /healthz. */
@RestController
public class HealthController {

    private final UrlStore store;

    public HealthController(UrlStore store) {
        this.store = store;
    }

    @GetMapping("/healthz")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("storedUrlCount", store.size());
        return body;
    }
}
