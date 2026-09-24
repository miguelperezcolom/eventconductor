package io.mateu.workflow.worker.http;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth 2.0 client credentials: a token per profile, fetched from its token endpoint, cached until
 * shortly before it expires, and dropped on demand (after a 401) so the next call fetches a new one.
 */
public final class OAuth2TokenCache {

    private record Token(String value, Instant expiresAt) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private final HttpClient client;

    public OAuth2TokenCache(HttpClient client) {
        this.client = client;
    }

    public String token(String profile, String tokenUri, String clientId, String clientSecret, String scope)
            throws IOException, InterruptedException {
        var cached = tokens.get(profile);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.value();
        }
        var form = "grant_type=client_credentials&client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret)
                + (scope == null || scope.isBlank() ? "" : "&scope=" + encode(scope));
        var response = client.send(HttpRequest.newBuilder(URI.create(tokenUri))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("token endpoint answered " + response.statusCode());
        }
        var body = JSON.readTree(response.body());
        var value = body.path("access_token").asText(null);
        if (value == null) {
            throw new IOException("token endpoint returned no access_token");
        }
        var lifetime = body.path("expires_in").asLong(300);
        tokens.put(profile, new Token(value, Instant.now().plusSeconds(Math.max(0, lifetime - 30))));
        return value;
    }

    public void invalidate(String profile) {
        tokens.remove(profile);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
