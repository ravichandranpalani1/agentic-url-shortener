package com.schwab.urlshortener;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests that start the real Spring Boot application on a random
 * port and drive it with {@link HttpClient} -- no mocking of the web layer,
 * so these exercise the exact wiring used in production. Uses the same
 * {@code java.net.http.HttpClient} approach as the earlier zero-dependency
 * version's integration test (rather than Spring's {@code TestRestTemplate})
 * specifically so redirect-following behavior is explicit and unambiguous:
 * {@code HttpClient.Redirect.NEVER} lets these tests assert 3xx status codes
 * and {@code Location} headers directly.
 *
 * <p>Rate limiting is configured generously here so it never interferes
 * with these lifecycle/behavior assertions; {@link RateLimitIntegrationTest}
 * exercises rate limiting itself with a deliberately small bucket.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UrlShortenerIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        Path dataDir = Files.createTempDirectory("uss-it-");
        registry.add("app.data-dir", dataDir::toString);
        registry.add("app.rate-limit.capacity", () -> 1000);
        registry.add("app.rate-limit.refill-per-second", () -> 1000.0);
    }

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void fullLifecycleCreateRedirectAnalyticsDelete() throws Exception {
        // 1) Create
        HttpResponse<String> createResponse = client.send(
                HttpRequest.newBuilder(URI.create(url("/api/v1/urls")))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"longUrl\":\"https://www.schwab.com/pricing\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, createResponse.statusCode(), "create should return 201");
        Map<String, Object> created = mapper.readValue(createResponse.body(), Map.class);
        String code = (String) created.get("code");
        assertTrue(code != null && !code.isBlank(), "created record should have a non-blank code");

        // 2) Redirect
        HttpResponse<Void> redirectResponse = client.send(
                HttpRequest.newBuilder(URI.create(url("/" + code))).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(302, redirectResponse.statusCode(), "redirect should return 302");
        assertEquals("https://www.schwab.com/pricing", redirectResponse.headers().firstValue("Location").orElse(null),
                "Location header should be the original long URL");

        // Analytics recording happens asynchronously; poll briefly instead of a fixed sleep.
        long deadline = System.currentTimeMillis() + 2000;
        long clicks = 0;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> info = client.send(
                    HttpRequest.newBuilder(URI.create(url("/api/v1/urls/" + code))).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            clicks = ((Number) mapper.readValue(info.body(), Map.class).get("clickCount")).longValue();
            if (clicks >= 1) {
                break;
            }
            Thread.sleep(25);
        }
        assertEquals(1L, clicks, "click count should reach 1 after the redirect was followed");

        // 3) Analytics endpoint
        HttpResponse<String> analytics = client.send(
                HttpRequest.newBuilder(URI.create(url("/api/v1/urls/" + code + "/analytics"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, analytics.statusCode(), "analytics endpoint should return 200");
        assertEquals(1, ((Number) mapper.readValue(analytics.body(), Map.class).get("totalClicks")).intValue(),
                "analytics totalClicks should be 1");

        // 4) Delete
        HttpResponse<Void> delete = client.send(
                HttpRequest.newBuilder(URI.create(url("/api/v1/urls/" + code))).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, delete.statusCode(), "delete should return 204");

        // 5) Redirect after delete should be 410 Gone
        HttpResponse<Void> afterDelete = client.send(
                HttpRequest.newBuilder(URI.create(url("/" + code))).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(410, afterDelete.statusCode(), "redirect to a deleted code should return 410");
    }

    @Test
    void redirectToUnknownCodeReturns404() throws Exception {
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create(url("/no-such-code"))).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(404, response.statusCode(), "unknown code should 404");
    }

    @Test
    void duplicateCustomAliasReturns409() throws Exception {
        String body = "{\"longUrl\":\"https://a.example.com\",\"customAlias\":\"dupe-it\"}";
        client.send(HttpRequest.newBuilder(URI.create(url("/api/v1/urls")))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.discarding());
        HttpResponse<String> second = client.send(
                HttpRequest.newBuilder(URI.create(url("/api/v1/urls")))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(409, second.statusCode(), "duplicate alias should return 409");
    }

    @Test
    void invalidUrlReturns400() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(url("/api/v1/urls")))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"longUrl\":\"not-a-url\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode(), "malformed longUrl should return 400");
    }

    @Test
    void healthEndpointReportsUp() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(url("/healthz"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("UP", mapper.readValue(response.body(), Map.class).get("status"));
    }
}
