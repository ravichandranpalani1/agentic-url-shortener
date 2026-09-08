package com.schwab.urlshortener.web;

import com.schwab.urlshortener.metrics.ServiceMetrics;
import com.schwab.urlshortener.model.ClickEvent;
import com.schwab.urlshortener.model.ServiceExceptions.UrlGoneException;
import com.schwab.urlshortener.model.ServiceExceptions.UrlNotFoundException;
import com.schwab.urlshortener.model.UrlRecord;
import com.schwab.urlshortener.store.UrlStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Handles GET /{code}: the actual redirect. Registered as a catch-all
 * single-segment path variable, which Spring MVC only matches when no more
 * specific mapping (e.g. /api/v1/urls, /healthz, /metrics) applies.
 *
 * <p>Click analytics are recorded on a background executor so a slow or
 * blocked analytics write can never add latency to the redirect itself --
 * the single most latency-sensitive path in the service.
 */
@RestController
public class RedirectController {

    private final UrlStore store;
    private final ServiceMetrics metrics;
    private final ExecutorService analyticsExecutor;

    public RedirectController(UrlStore store, ServiceMetrics metrics, ExecutorService analyticsExecutor) {
        this.store = store;
        this.metrics = metrics;
        this.analyticsExecutor = analyticsExecutor;
    }

    @GetMapping("/")
    public Map<String, Object> root() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "agentic-url-shortener");
        body.put("status", "ok");
        return body;
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest httpRequest) {
        UrlRecord record = store.find(code).orElseThrow(() -> new UrlNotFoundException("No such short code: " + code));
        long now = System.currentTimeMillis();
        if (!record.active()) {
            throw new UrlGoneException("Short code has been deleted: " + code);
        }
        if (record.isExpired(now)) {
            throw new UrlGoneException("Short code has expired: " + code);
        }

        metrics.recordRedirect();

        String referrer = httpRequest.getHeader("Referer");
        String userAgent = httpRequest.getHeader("User-Agent");
        String clientHash = ClientHash.of(clientKey(httpRequest));
        analyticsExecutor.submit(() -> {
            try {
                store.recordClick(new ClickEvent(code, now, referrer, userAgent, clientHash));
            } catch (RuntimeException e) {
                // Analytics is best-effort: a failure here must never surface to the client,
                // who has already received their redirect.
                System.err.println("Failed to record click for " + code + ": " + e.getMessage());
            }
        });

        return ResponseEntity.status(HttpStatus.FOUND)
                .headers(headers -> headers.setLocation(URI.create(record.longUrl())))
                .build();
    }

    private static String clientKey(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
