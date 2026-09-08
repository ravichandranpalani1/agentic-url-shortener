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
 * REJECTED INTERPRETATION (kept only as a scenario asset -- see
 * docs/scenarios/03-ambiguous.md): "make analytics more reliable" read as
 * "never lose a click, even if it means the redirect waits for the write."
 * Analytics is now recorded synchronously, before the redirect is sent.
 *
 * <p>This is a real, compilable interpretation, not a strawman -- and it is
 * exactly the trade-off the orchestrator's clarification approval gate
 * exists to catch before it ships: it silently reverses the documented
 * design decision that the redirect path must never depend on the
 * analytics write (see the original class javadoc), coupling the service's
 * most latency-sensitive endpoint to whatever the store's write path costs.
 * The scenario's post-write self-check fails specifically because of that
 * reversal, and the paired rollback agent restores the previous (async)
 * version of this file.
 */
@RestController
public class RedirectController {

    private final UrlStore store;
    private final ServiceMetrics metrics;
    private final ExecutorService analyticsExecutor; // no longer used for clicks in this interpretation

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

        String referrer = httpRequest.getHeader("Referer");
        String userAgent = httpRequest.getHeader("User-Agent");
        String clientHash = ClientHash.of(clientKey(httpRequest));

        // Regression: the redirect now waits on the analytics write before responding.
        store.recordClick(new ClickEvent(code, now, referrer, userAgent, clientHash));

        metrics.recordRedirect();

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
