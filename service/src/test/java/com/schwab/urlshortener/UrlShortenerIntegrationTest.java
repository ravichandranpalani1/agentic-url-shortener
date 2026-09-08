package com.schwab.urlshortener;

import com.schwab.common.json.Json;
import com.schwab.testlib.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static com.schwab.testlib.Assert.assertEquals;
import static com.schwab.testlib.Assert.assertTrue;

/**
 * End-to-end tests that start a real {@link Bootstrap} server on an
 * ephemeral port and drive it with {@link HttpClient} -- no mocking of the
 * HTTP layer, so these exercise the exact wiring used in production.
 */
public class UrlShortenerIntegrationTest {

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private Bootstrap.Running startServer() throws IOException {
        return startServer(20, 5.0);
    }

    private Bootstrap.Running startServer(long rateLimitCapacity, double refillPerSec) throws IOException {
        Path dataDir = Files.createTempDirectory("uss-it-");
        Bootstrap.Config config = new Bootstrap.Config(0, dataDir.toString(), "http://localhost", rateLimitCapacity,
                refillPerSec, 4);
        return Bootstrap.start(config);
    }

    @Test
    public void fullLifecycle_createRedirectAnalyticsDelete() throws Exception {
        Bootstrap.Running server = startServer();
        try {
            int port = server.port();

            // 1) Create
            HttpResponse<String> createResponse = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/urls"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"longUrl\":\"https://www.schwab.com/pricing\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, createResponse.statusCode(), "create should return 201");
            Map<String, Object> created = Json.parseObject(createResponse.body());
            String code = (String) created.get("code");
            assertTrue(code != null && !code.isBlank(), "created record should have a non-blank code");

            // 2) Redirect
            HttpResponse<Void> redirectResponse = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + code)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(302, redirectResponse.statusCode(), "redirect should return 302");
            assertEquals("https://www.schwab.com/pricing", redirectResponse.headers().firstValue("Location").orElse(null),
                    "Location header should be the original long URL");

            // Analytics recording happens asynchronously; poll briefly instead of a fixed sleep.
            long deadline = System.currentTimeMillis() + 2000;
            long clicks = 0;
            while (System.currentTimeMillis() < deadline) {
                HttpResponse<String> info = client.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/urls/" + code)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                clicks = ((Number) Json.parseObject(info.body()).get("clickCount")).longValue();
                if (clicks >= 1) {
                    break;
                }
                Thread.sleep(25);
            }
            assertEquals(1L, clicks, "click count should reach 1 after the redirect was followed");

            // 3) Analytics endpoint
            HttpResponse<String> analytics = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/urls/" + code + "/analytics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, analytics.statusCode(), "analytics endpoint should return 200");
            assertEquals(1.0, Json.parseObject(analytics.body()).get("totalClicks"), "analytics totalClicks should be 1");

            // 4) Delete
            HttpResponse<Void> delete = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/urls/" + code)).DELETE().build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(204, delete.statusCode(), "delete should return 204");

            // 5) Redirect after delete should be 410 Gone
            HttpResponse<Void> afterDelete = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + code)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(410, afterDelete.statusCode(), "redirect to a deleted code should return 410");
        } finally {
            server.stop();
        }
    }

    @Test
    public void redirectToUnknownCodeReturns404() throws Exception {
        Bootstrap.Running server = startServer();
        try {
            HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/no-such-code")).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(404, response.statusCode(), "unknown code should 404");
        } finally {
            server.stop();
        }
    }

    @Test
    public void duplicateCustomAliasReturns409() throws Exception {
        Bootstrap.Running server = startServer();
        try {
            int port = server.port();
            String body = "{\"longUrl\":\"https://a.example.com\",\"customAlias\":\"dupe-it\"}";
            client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/urls"))
                            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.discarding());
            HttpResponse<String> second = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/urls"))
                            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(409, second.statusCode(), "duplicate alias should return 409");
        } finally {
            server.stop();
        }
    }

    @Test
    public void invalidUrlReturns400() throws Exception {
        Bootstrap.Running server = startServer();
        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/api/v1/urls"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"longUrl\":\"not-a-url\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode(), "malformed longUrl should return 400");
        } finally {
            server.stop();
        }
    }

    @Test
    public void rateLimitTripsAfterCapacityExceeded() throws Exception {
        Bootstrap.Running server = startServer(3, 0.0001); // capacity 3, effectively no refill during the test
        try {
            int port = server.port();
            int sawRateLimited = 0;
            for (int i = 0; i < 6; i++) {
                HttpResponse<Void> r = client.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/rl-probe-" + i))
                                .GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (r.statusCode() == 429) {
                    sawRateLimited++;
                }
            }
            assertTrue(sawRateLimited > 0, "at least one of 6 requests against a capacity-3 bucket should be rate limited");
        } finally {
            server.stop();
        }
    }
}
