package com.schwab.urlshortener;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the {@code ServiceGovernanceFilter}'s rate limiting with a
 * deliberately small, effectively-no-refill bucket -- kept as its own
 * Spring context (a distinct set of {@code @DynamicPropertySource} values)
 * so it never competes with {@link UrlShortenerIntegrationTest}'s generous
 * rate limit for the same bucket.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        Path dataDir = Files.createTempDirectory("uss-it-ratelimit-");
        registry.add("app.data-dir", dataDir::toString);
        registry.add("app.rate-limit.capacity", () -> 3);
        registry.add("app.rate-limit.refill-per-second", () -> 0.0001); // effectively no refill during the test
    }

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void rateLimitTripsAfterCapacityExceeded() throws Exception {
        int sawRateLimited = 0;
        for (int i = 0; i < 6; i++) {
            HttpResponse<Void> r = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/rl-probe-" + i)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() == 429) {
                sawRateLimited++;
            }
        }
        assertTrue(sawRateLimited > 0, "at least one of 6 requests against a capacity-3 bucket should be rate limited");
    }
}
