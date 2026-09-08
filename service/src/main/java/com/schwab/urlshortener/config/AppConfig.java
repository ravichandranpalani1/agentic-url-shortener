package com.schwab.urlshortener.config;

import com.schwab.urlshortener.rate.RateLimiter;
import com.schwab.urlshortener.store.InMemoryUrlStore;
import com.schwab.urlshortener.store.UrlStore;
import com.schwab.urlshortener.store.WriteAheadLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wires the service's core dependencies as Spring beans. This is the direct
 * Spring Boot analogue of what {@code Bootstrap.start(Config)} did in the
 * earlier zero-dependency version of this service: the domain classes
 * themselves ({@link WriteAheadLog}, {@link InMemoryUrlStore},
 * {@link RateLimiter}) are unchanged plain Java, only the wiring mechanism
 * changed from hand-written code to a Spring {@code @Configuration} class.
 */
@Configuration
public class AppConfig {

    @Value("${app.data-dir:./data}")
    private String dataDir;

    @Value("${app.rate-limit.capacity:20}")
    private long rateLimitCapacity;

    @Value("${app.rate-limit.refill-per-second:5.0}")
    private double rateLimitRefillPerSecond;

    @Bean
    public WriteAheadLog writeAheadLog() {
        return new WriteAheadLog(Path.of(dataDir, "wal.log"));
    }

    @Bean
    public UrlStore urlStore(WriteAheadLog writeAheadLog) {
        return new InMemoryUrlStore(writeAheadLog);
    }

    @Bean
    public RateLimiter rateLimiter() {
        // Idle buckets are evicted after 10 minutes of inactivity -- see RateLimiter's javadoc.
        return new RateLimiter(rateLimitCapacity, rateLimitRefillPerSecond, 10 * 60 * 1000L);
    }

    /**
     * Click analytics are recorded off the request thread so a slow or blocked write can never
     * add latency to the redirect itself -- see RedirectController and
     * docs/scenarios/03-ambiguous.md for the human-approved design decision this preserves.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService analyticsExecutor() {
        return Executors.newFixedThreadPool(2);
    }
}
