package com.schwab.urlshortener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.model.ServiceExceptions.InvalidUrlException;
import com.schwab.urlshortener.validation.AliasValidator;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the brownfield GET /api/v1/urls/expired addition: applied for real
 * by the orchestrator's brownfield scenario (see
 * docs/scenarios/02-brownfield.md), not hand-written after the fact. Copied
 * onto service/src/test/java/com/schwab/urlshortener/ExpiredUrlsFeatureTest.java
 * by ImplementationAgent, alongside the UrlItemController and AliasValidator
 * changes in this same directory.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExpiredUrlsFeatureTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        Path dataDir = Files.createTempDirectory("uss-brownfield-it-");
        registry.add("app.data-dir", dataDir::toString);
        registry.add("app.rate-limit.capacity", () -> 1000);
        registry.add("app.rate-limit.refill-per-second", () -> 1000.0);
    }

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void expiredAliasIsRejectedByValidator() {
        assertThrows(InvalidUrlException.class, () -> AliasValidator.validate("expired"),
                "'expired' must be reserved so it can never be shadowed by GET /api/v1/urls/expired");
    }

    @Test
    void expiredEndpointListsOnlyExpiredActiveRecords() throws Exception {
        // A record with a 1-second TTL that we wait out.
        postCreate("https://schwab.com/expiring-soon", null, 1L);
        // A record with no TTL -- must never show up as expired.
        postCreate("https://schwab.com/forever", "forever-link", null);

        long deadline = System.currentTimeMillis() + 3000;
        List<?> items = List.of();
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/urls/expired")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode(), "expired-list endpoint should return 200");
            Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
            items = (List<?>) body.get("items");
            if (!items.isEmpty()) {
                break;
            }
            Thread.sleep(100);
        }
        assertEquals(1, items.size(), "exactly the one 1-second-TTL record should be listed as expired");

        @SuppressWarnings("unchecked")
        Map<String, Object> expiredEntry = (Map<String, Object>) items.get(0);
        assertTrue(((String) expiredEntry.get("longUrl")).contains("expiring-soon"),
                "the expired entry should be the short-TTL record, not the permanent one");
    }

    private void postCreate(String longUrl, String alias, Long ttlSeconds) throws Exception {
        StringBuilder json = new StringBuilder("{\"longUrl\":\"").append(longUrl).append("\"");
        if (alias != null) {
            json.append(",\"customAlias\":\"").append(alias).append("\"");
        }
        if (ttlSeconds != null) {
            json.append(",\"ttlSeconds\":").append(ttlSeconds);
        }
        json.append("}");
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/urls"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, resp.statusCode(), "setup create should succeed: " + resp.body());
    }
}
