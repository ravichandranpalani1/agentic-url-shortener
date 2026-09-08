package com.schwab.urlshortener.web;

import com.schwab.urlshortener.model.ClickEvent;
import com.schwab.urlshortener.model.ServiceExceptions.UrlNotFoundException;
import com.schwab.urlshortener.model.UrlRecord;
import com.schwab.urlshortener.store.UrlStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles the /api/v1/urls/{code} and /api/v1/urls/{code}/analytics
 * resources: GET (info), GET .../analytics, DELETE (soft delete).
 */
@RestController
public class UrlItemController {

    private final UrlStore store;

    public UrlItemController(UrlStore store) {
        this.store = store;
    }

    @GetMapping("/api/v1/urls/{code}")
    public Map<String, Object> info(@PathVariable String code, HttpServletRequest httpRequest) {
        UrlRecord record = find(code);
        return UrlsController.toResponseBody(record, httpRequest);
    }

    @GetMapping("/api/v1/urls/{code}/analytics")
    public Map<String, Object> analytics(@PathVariable String code) {
        UrlRecord record = find(code);
        List<ClickEvent> recent = store.recentClicks(code, 20);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("totalClicks", record.clickCount());
        body.put("recentClicks", recent.stream().map(UrlItemController::toClickBody).toList());
        return body;
    }

    @DeleteMapping("/api/v1/urls/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        boolean removed = store.softDelete(code);
        if (!removed) {
            throw new UrlNotFoundException("No such short code: " + code);
        }
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    private UrlRecord find(String code) {
        return store.find(code).orElseThrow(() -> new UrlNotFoundException("No such short code: " + code));
    }

    private static Map<String, Object> toClickBody(ClickEvent event) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", event.timestampMillis());
        m.put("referrer", event.referrer());
        m.put("userAgent", event.userAgent());
        m.put("clientHash", event.clientHash());
        return m;
    }
}
