package io.mateu.workflow.worker.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.worker.api.TaskFailure;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.function.LongSupplier;

/**
 * OAuth 2.0 client credentials: a token per profile, fetched from its token endpoint, cached until
 * shortly before it expires, and dropped on demand (after a 401) so the next call fetches a new one.
 *
 * <p>A token endpoint that comes from a definition (an inline auth block) is untrusted like any URL in
 * a definition: it is checked with the {@link HostGuard} before the client secret is posted to it. The
 * token response is read up to a cap, so a hostile endpoint cannot exhaust the worker's memory.
 */
public final class OAuth2TokenCache {

    private record Token(String value, Instant expiresAt) {
    }

    /** The cap used when none is given: generous for any token response. */
    static final long DEFAULT_MAX_TOKEN_RESPONSE_BYTES = 1_048_576;

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private final HttpClient client;
    private final LongSupplier maxResponseBytes;

    public OAuth2TokenCache(HttpClient client) {
        this(client, () -> DEFAULT_MAX_TOKEN_RESPONSE_BYTES);
    }

    public OAuth2TokenCache(HttpClient client, LongSupplier maxResponseBytes) {
        this.client = client;
        this.maxResponseBytes = maxResponseBytes;
    }

    /** A token from a trusted (configured) token endpoint. */
    public String token(String profile, String tokenUri, String clientId, String clientSecret, String scope)
            throws IOException, InterruptedException {
        return token(profile, tokenUri, clientId, clientSecret, scope, null);
    }

    /**
     * A token for {@code profile}; when {@code guard} is not null the token endpoint must pass it (an
     * endpoint from a definition), and is refused with {@code HTTP_FORBIDDEN_HOST} otherwise.
     */
    public String token(String profile, String tokenUri, String clientId, String clientSecret, String scope,
                        HostGuard guard) throws IOException, InterruptedException {
        var cached = tokens.get(profile);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.value();
        }
        if (tokenUri == null || tokenUri.isBlank()) {
            throw new TaskFailure("HTTP_CONFIG", "the oauth2-client-credentials auth has no token-uri", false);
        }
        URI uri;
        try {
            uri = URI.create(tokenUri);
        } catch (IllegalArgumentException e) {
            throw new TaskFailure("HTTP_CONFIG", "the token-uri is not a valid URL", false);
        }
        if (guard != null) {
            var refusal = guard.check(uri);
            if (refusal != null) {
                if (refusal.unresolved()) {
                    throw new IOException("token endpoint: " + refusal.reason());
                }
                throw new TaskFailure("HTTP_FORBIDDEN_HOST", "token endpoint: " + refusal.reason(), false);
            }
        }
        var form = "grant_type=client_credentials&client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret)
                + (scope == null || scope.isBlank() ? "" : "&scope=" + encode(scope));
        var response = client.send(HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        var bodyText = readCapped(response.body());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("token endpoint answered " + response.statusCode());
        }
        var body = JSON.readTree(bodyText);
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

    private String readCapped(InputStream body) throws IOException {
        var max = maxResponseBytes.getAsLong();
        try (body) {
            var bytes = body.readNBytes((int) Math.min(Integer.MAX_VALUE - 1, max + 1));
            if (bytes.length > max) {
                throw new TaskFailure("HTTP_RESPONSE_TOO_LARGE", "the token endpoint's response is larger than "
                        + max + " bytes (workflow.http.max-response-bytes)", false);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
