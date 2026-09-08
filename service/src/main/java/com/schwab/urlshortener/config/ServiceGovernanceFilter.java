package com.schwab.urlshortener.config;

import com.schwab.urlshortener.metrics.ServiceMetrics;
import com.schwab.urlshortener.rate.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * A single servlet filter that applies per-client rate limiting and records
 * per-request reliability metrics ahead of every controller -- the Spring
 * equivalent of the {@code RateLimitFilter} (a {@code com.sun.net.httpserver.Filter})
 * and the per-handler {@code metrics.recordRequest(...)} calls the earlier
 * zero-dependency version made individually in each HTTP handler's
 * {@code finally} block.
 *
 * <p>{@code /healthz} and {@code /metrics} are exempt from both, matching the
 * original service's behavior: liveness/metrics checks should never be rate
 * limited, and checking them should not itself count as a "request" in the
 * reliability metrics it reports.
 */
@Component
public final class ServiceGovernanceFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final ServiceMetrics metrics;

    public ServiceGovernanceFilter(RateLimiter rateLimiter, ServiceMetrics metrics) {
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("/healthz".equals(path) || "/metrics".equals(path)) {
            chain.doFilter(request, response);
            return;
        }

        String clientKey = clientKey(request);
        if (!rateLimiter.tryConsume(clientKey)) {
            metrics.recordRateLimited();
            response.setHeader("Retry-After", "1");
            response.setStatus(429);
            response.setContentType("application/json; charset=utf-8");
            response.getWriter().write(
                    "{\"error\":\"RATE_LIMITED\",\"message\":\"Too many requests from this client, try again shortly\"}");
            return;
        }

        long start = System.currentTimeMillis();
        chain.doFilter(request, response);
        long latency = System.currentTimeMillis() - start;
        metrics.recordRequest(latency, response.getStatus() >= 400);
    }

    private static String clientKey(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
