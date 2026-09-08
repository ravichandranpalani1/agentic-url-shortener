package com.schwab.urlshortener;

import com.schwab.urlshortener.http.HealthHandler;
import com.schwab.urlshortener.http.MetricsHandler;
import com.schwab.urlshortener.http.RateLimitFilter;
import com.schwab.urlshortener.http.RedirectHandler;
import com.schwab.urlshortener.http.UrlItemHandler;
import com.schwab.urlshortener.http.UrlsCollectionHandler;
import com.schwab.urlshortener.metrics.ServiceMetrics;
import com.schwab.urlshortener.rate.RateLimiter;
import com.schwab.urlshortener.store.InMemoryUrlStore;
import com.schwab.urlshortener.store.UrlStore;
import com.schwab.urlshortener.store.WriteAheadLog;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Wires the service's dependencies and starts the HTTP server. Extracted
 * from {@link UrlShortenerServer#main} so integration tests can start a
 * real server on an ephemeral port (port 0) without duplicating the wiring.
 */
public final class Bootstrap {

    public record Config(int port, String dataDir, String publicBaseUrl, long rateLimitCapacity,
                          double rateLimitRefillPerSec, int httpThreads) {
        public static Config defaults(int port, String dataDir) {
            return new Config(port, dataDir, "http://localhost:" + port, 20, 5.0, 4);
        }
    }

    public static final class Running {
        public final HttpServer server;
        public final UrlStore store;
        public final ServiceMetrics metrics;
        private final ExecutorService httpExecutor;
        private final ExecutorService analyticsExecutor;

        Running(HttpServer server, UrlStore store, ServiceMetrics metrics,
                ExecutorService httpExecutor, ExecutorService analyticsExecutor) {
            this.server = server;
            this.store = store;
            this.metrics = metrics;
            this.httpExecutor = httpExecutor;
            this.analyticsExecutor = analyticsExecutor;
        }

        public int port() {
            return server.getAddress().getPort();
        }

        public void stop() {
            server.stop(1);
            shutdown(httpExecutor);
            shutdown(analyticsExecutor);
        }

        private static void shutdown(ExecutorService executor) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public static Running start(Config config) throws IOException {
        WriteAheadLog wal = new WriteAheadLog(Path.of(config.dataDir(), "wal.log"));
        UrlStore store = new InMemoryUrlStore(wal);
        ServiceMetrics metrics = new ServiceMetrics();
        RateLimiter rateLimiter = new RateLimiter(config.rateLimitCapacity(), config.rateLimitRefillPerSec(),
                10 * 60 * 1000L);

        ExecutorService httpExecutor = Executors.newFixedThreadPool(config.httpThreads());
        ExecutorService analyticsExecutor = Executors.newFixedThreadPool(2);

        HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        server.setExecutor(httpExecutor);

        RateLimitFilter rateLimitFilter = new RateLimitFilter(rateLimiter, metrics);
        server.createContext("/api/v1/urls", new UrlsCollectionHandler(store, metrics, config.publicBaseUrl()))
                .getFilters().add(rateLimitFilter);
        server.createContext("/api/v1/urls/", new UrlItemHandler(store, metrics, config.publicBaseUrl()))
                .getFilters().add(rateLimitFilter);
        server.createContext("/healthz", new HealthHandler(store));
        server.createContext("/metrics", new MetricsHandler(metrics, store));
        server.createContext("/", new RedirectHandler(store, metrics, analyticsExecutor))
                .getFilters().add(rateLimitFilter);

        server.start();
        return new Running(server, store, metrics, httpExecutor, analyticsExecutor);
    }
}
