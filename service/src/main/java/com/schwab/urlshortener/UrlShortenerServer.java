package com.schwab.urlshortener;

import java.io.IOException;

/**
 * Process entry point: reads configuration from the environment and starts
 * the service via {@link Bootstrap}.
 *
 * <p>Zero third-party dependencies by design (see docs/testing-and-limitations.md):
 * the HTTP layer is {@code com.sun.net.httpserver}, persistence is a
 * write-ahead log replayed into an in-memory store, and JSON is hand-rolled
 * in the {@code common} module.
 */
public final class UrlShortenerServer {

    public static void main(String[] args) throws IOException {
        int port = intEnv("PORT", 8080);
        String dataDir = env("DATA_DIR", "./data");
        String publicBaseUrl = env("PUBLIC_BASE_URL", "http://localhost:" + port);
        long rateLimitCapacity = longEnv("RATE_LIMIT_CAPACITY", 20);
        double rateLimitRefillPerSec = doubleEnv("RATE_LIMIT_REFILL_PER_SEC", 5.0);
        int httpThreads = intEnv("HTTP_THREADS", 8);

        Bootstrap.Config config = new Bootstrap.Config(port, dataDir, publicBaseUrl, rateLimitCapacity,
                rateLimitRefillPerSec, httpThreads);
        Bootstrap.Running running = Bootstrap.start(config);

        System.out.println("agentic-url-shortener listening on port " + running.port()
                + " (data dir: " + dataDir + ", stored URLs on startup: " + running.store.size() + ")");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            running.stop();
        }));
    }

    private static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    private static int intEnv(String key, int defaultValue) {
        String v = System.getenv(key);
        return v == null ? defaultValue : Integer.parseInt(v);
    }

    private static long longEnv(String key, long defaultValue) {
        String v = System.getenv(key);
        return v == null ? defaultValue : Long.parseLong(v);
    }

    private static double doubleEnv(String key, double defaultValue) {
        String v = System.getenv(key);
        return v == null ? defaultValue : Double.parseDouble(v);
    }
}
