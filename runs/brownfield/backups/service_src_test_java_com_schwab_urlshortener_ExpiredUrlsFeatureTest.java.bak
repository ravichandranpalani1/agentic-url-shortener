package com.schwab.urlshortener;

import com.schwab.common.json.Json;
import com.schwab.testlib.Test;
import com.schwab.urlshortener.model.ServiceExceptions.InvalidUrlException;
import com.schwab.urlshortener.validation.AliasValidator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.schwab.testlib.Assert.assertEquals;
import static com.schwab.testlib.Assert.assertThrows;
import static com.schwab.testlib.Assert.assertTrue;

/**
 * Covers the brownfield GET /api/v1/urls/expired addition: applied for real
 * by the orchestrator's brownfield scenario (see
 * docs/scenarios/02-brownfield.md), not hand-written after the fact.
 */
public class ExpiredUrlsFeatureTest {

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    public void expiredAliasIsRejectedByValidator() {
        assertThrows(InvalidUrlException.class, () -> AliasValidator.validate("expired"),
                "'expired' must be reserved so it can never shadow GET /api/v1/urls/expired");
        assertThrows(InvalidUrlException.class, () -> AliasValidator.validate("expired-links"),
                "aliases starting with 'expired' must also be rejected");
    }

    @Test
    public void expiredEndpointListsOnlyExpiredActiveRecords() throws Exception {
        Path dataDir = Files.createTempDirectory("uss-brownfield-it-");
        Bootstrap.Config config = new Bootstrap.Config(0, dataDir.toString(), "http://localhost", 50, 20.0, 4);
        Bootstrap.Running server = Bootstrap.start(config);
        try {
            int port = server.port();

            // A record with a 1-second TTL that we wait out.
            postCreate(port, "https://schwab.com/expiring-soon", null, 1L);
            // A record with no TTL -- must never show up as expired.
            postCreate(port, "https://schwab.com/forever", "forever-link", null);

            long deadline = System.currentTimeMillis() + 3000;
            List<?> items = List.of();
            while (System.currentTimeMillis() < deadline) {
                HttpResponse<String> resp = client.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/urls/expired")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, resp.statusCode(), "expired-list endpoint should return 200");
                Map<String, Object> body = Json.parseObject(resp.body());
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
        } finally {
            server.stop();
        }
    }

    private void postCreate(int port, String longUrl, String alias, Long ttlSeconds) throws Exception {
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
