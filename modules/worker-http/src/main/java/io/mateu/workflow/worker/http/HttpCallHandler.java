package io.mateu.workflow.worker.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.template.Templates;
import io.mateu.workflow.worker.api.TaskContext;
import io.mateu.workflow.worker.api.TaskFailure;
import io.mateu.workflow.worker.api.TaskHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The built-in {@code http-call} task: makes the request an HTTP_CALL step's engine rendered, and maps
 * the response back into process variables.
 *
 * <ul>
 *   <li>A status in {@code successStatus} (default: any 2xx) completes the step with the
 *       {@code output} expressions evaluated over {@code {status, headers, body}} (the body parsed when
 *       it is JSON).</li>
 *   <li>Anything else fails it with code {@code HTTP_<status>} and the start of the body as the reason;
 *       an I/O error or timeout fails it with {@code HTTP_IO}. Whether the step's {@code retries} apply
 *       is {@code retryOn} (default {@code [5xx, io]}): a failure outside it is final.</li>
 *   <li>The step execution id goes out as {@code Idempotency-Key}, the same on every retry of the step,
 *       so a server that honours it applies a retried call once.</li>
 * </ul>
 */
public class HttpCallHandler implements TaskHandler<HttpCallHandler.Input, Map<String, Object>> {

    /**
     * The task variable the engine renders the request into. The task carries every process variable
     * too; they are the engine's business here (the request is already rendered), so they are ignored.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Input(JsonNode __http) {
    }

    private static final Logger log = LoggerFactory.getLogger(HttpCallHandler.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int REASON_BODY_CHARS = 1_000;

    private final HttpWorkerProperties properties;
    private final SecretResolver secrets;
    private final HostGuard hostGuard;
    private final OAuth2TokenCache tokens;
    private final HttpCallMetrics metrics;
    private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();

    public HttpCallHandler(HttpWorkerProperties properties, SecretResolver secrets, HostGuard hostGuard,
                           HttpCallMetrics metrics) {
        this.properties = properties;
        this.secrets = secrets;
        this.hostGuard = hostGuard;
        this.metrics = metrics == null ? HttpCallMetrics.NONE : metrics;
        this.tokens = new OAuth2TokenCache(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    @Override
    public Map<String, Object> handle(Input input, TaskContext context) throws Exception {
        var request = input == null ? null : input.__http();
        if (request == null || !request.isObject()) {
            throw new TaskFailure("HTTP_REQUEST", "the task carries no rendered HTTP request", false);
        }
        var retryOn = new ArrayList<String>();
        request.path("retryOn").forEach(node -> retryOn.add(node.asText()));
        if (retryOn.isEmpty() && request.path("retryOn").isMissingNode()) {
            retryOn.addAll(List.of("5xx", "io"));
        }
        var connectionName = text(request, "connection");
        var connection = connectionName == null ? null : properties.getConnections().get(connectionName);
        if (connectionName != null && connection == null) {
            throw new TaskFailure("HTTP_CONFIG", "connection '" + connectionName + "' is not configured"
                    + " (workflow.http.connections." + connectionName + ")", false);
        }
        URI uri = uriOf(request, connection);
        if (connection == null) {
            var refusal = hostGuard.refusal(uri);
            if (refusal != null) {
                throw new TaskFailure("HTTP_FORBIDDEN_HOST", refusal, false);
            }
        }
        var method = text(request, "method") == null ? "GET" : text(request, "method");
        var auth = authOf(request, connection);
        var timeout = connection == null ? properties.getReadTimeout() : connection.getReadTimeout();
        var client = clientFor(connectionName, connection);
        var label = connectionName == null ? uri.getHost() : connectionName;

        var started = System.nanoTime();
        HttpResponse<InputStream> response;
        try {
            response = send(client, build(request, connection, uri, method, auth, timeout));
            if (response.statusCode() == 401 && auth != null && "oauth2-client-credentials".equals(auth.profile().getType())) {
                // The cached token may have been revoked or rotated: fetch a fresh one, once.
                response.body().close();
                tokens.invalidate(auth.name());
                response = send(client, build(request, connection, uri, method, auth, timeout));
            }
        } catch (HttpTimeoutException e) {
            metrics.call(label, "timeout", System.nanoTime() - started);
            throw failure("HTTP_IO", method + " " + redacted(uri) + " timed out", retryOn.contains("io"));
        } catch (IOException e) {
            metrics.call(label, "io", System.nanoTime() - started);
            throw failure("HTTP_IO", method + " " + redacted(uri) + " failed: " + e, retryOn.contains("io"));
        }
        var status = response.statusCode();
        var body = readCapped(response.body());
        metrics.call(label, String.valueOf(status), System.nanoTime() - started);
        log.info("HTTP_CALL {} {} → {} in {} ms (step {})", method, redacted(uri), status,
                (System.nanoTime() - started) / 1_000_000, context == null ? null : context.stepId());

        if (!isSuccess(status, request.path("successStatus"))) {
            var excerpt = body.length() > REASON_BODY_CHARS ? body.substring(0, REASON_BODY_CHARS) + "…" : body;
            throw failure("HTTP_" + status, method + " " + redacted(uri) + " answered " + status
                    + (excerpt.isBlank() ? "" : ": " + excerpt), retryable(status, retryOn));
        }
        return outputs(request.path("output"), status, response.headers().map(), body);
    }

    private HttpResponse<InputStream> send(HttpClient client, HttpRequest request) throws IOException {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private HttpRequest build(JsonNode request, HttpWorkerProperties.Connection connection, URI uri, String method,
                              ResolvedAuth auth, Duration timeout) throws IOException {
        var builder = HttpRequest.newBuilder().timeout(timeout);
        var headers = new LinkedHashMap<String, String>();
        if (connection != null) {
            headers.putAll(connection.getHeaders());
        }
        request.path("headers").properties().forEach(entry -> headers.put(entry.getKey(), entry.getValue().asText()));
        var idempotencyHeader = connection == null ? "Idempotency-Key" : connection.getIdempotencyHeader();
        var idempotencyKey = text(request, "idempotencyKey");
        if (idempotencyKey != null && idempotencyHeader != null && !"none".equalsIgnoreCase(idempotencyHeader)) {
            headers.putIfAbsent(idempotencyHeader, idempotencyKey);
        }
        var body = text(request, "body");
        if (body != null && headers.keySet().stream().noneMatch(name -> name.equalsIgnoreCase("Content-Type"))) {
            headers.put("Content-Type", "json".equals(text(request, "bodyKind")) ? "application/json" : "text/plain; charset=utf-8");
        }
        var target = uri;
        if (auth != null) {
            target = auth.apply(headers, uri);
        }
        headers.forEach(builder::header);
        builder.uri(target);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return builder.build();
    }

    private URI uriOf(JsonNode request, HttpWorkerProperties.Connection connection) {
        String base;
        if (connection != null) {
            var path = text(request, "path");
            var root = connection.getBaseUrl() == null ? "" : connection.getBaseUrl();
            base = path == null || path.isBlank() ? root
                    : (root.endsWith("/") ? root.substring(0, root.length() - 1) : root) + (path.startsWith("/") ? path : "/" + path);
        } else {
            base = text(request, "url");
            if (base == null) {
                throw new TaskFailure("HTTP_REQUEST", "the request has neither a connection nor a url", false);
            }
        }
        var query = new StringBuilder();
        request.path("query").properties().forEach(entry -> {
            query.append(query.isEmpty() ? "" : "&")
                    .append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(entry.getValue().asText(), StandardCharsets.UTF_8));
        });
        var full = query.isEmpty() ? base : base + (base.contains("?") ? "&" : "?") + query;
        try {
            return URI.create(full);
        } catch (IllegalArgumentException e) {
            throw new TaskFailure("HTTP_REQUEST", "not a valid URL: " + full, false);
        }
    }

    /** The auth to apply: the step's (profile name or inline block), else the connection's default. */
    private ResolvedAuth authOf(JsonNode request, HttpWorkerProperties.Connection connection) {
        var node = request.path("auth");
        if (node.isTextual()) {
            return profile(node.asText());
        }
        if (node.isObject()) {
            var inline = JSON.convertValue(node, HttpWorkerProperties.AuthProfile.class);
            return new ResolvedAuth("inline:" + node.hashCode(), inline);
        }
        if (connection != null && connection.getAuth() != null && !connection.getAuth().isBlank()) {
            return profile(connection.getAuth());
        }
        return null;
    }

    private ResolvedAuth profile(String name) {
        var profile = properties.getAuth().get(name);
        if (profile == null) {
            throw new TaskFailure("HTTP_CONFIG", "auth profile '" + name + "' is not configured (workflow.http.auth."
                    + name + ")", false);
        }
        return new ResolvedAuth(name, profile);
    }

    /** An auth profile, applied with its secrets resolved at the moment of the call. */
    private final class ResolvedAuth {
        private final String name;
        private final HttpWorkerProperties.AuthProfile profile;

        ResolvedAuth(String name, HttpWorkerProperties.AuthProfile profile) {
            this.name = name;
            this.profile = profile;
        }

        String name() {
            return name;
        }

        HttpWorkerProperties.AuthProfile profile() {
            return profile;
        }

        URI apply(Map<String, String> headers, URI uri) throws IOException {
            try {
                switch (profile.getType() == null ? "none" : profile.getType()) {
                    case "none" -> { }
                    case "basic" -> headers.put("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(
                            (secrets.resolve(profile.getUsername()) + ":" + secrets.resolve(profile.getPassword()))
                                    .getBytes(StandardCharsets.UTF_8)));
                    case "bearer" -> headers.put("Authorization", "Bearer " + secrets.resolve(profile.getToken()));
                    case "api-key" -> {
                        var value = secrets.resolve(profile.getValue());
                        if ("query".equals(profile.getIn())) {
                            var separator = uri.getRawQuery() == null ? "?" : "&";
                            return URI.create(uri + separator + URLEncoder.encode(profile.getHeader(), StandardCharsets.UTF_8)
                                    + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
                        }
                        headers.put(profile.getHeader(), value);
                    }
                    case "oauth2-client-credentials" -> headers.put("Authorization", "Bearer " + tokens.token(name,
                            profile.getTokenUri(), secrets.resolve(profile.getClientId()),
                            secrets.resolve(profile.getClientSecret()), profile.getScope()));
                    default -> throw new TaskFailure("HTTP_CONFIG", "unknown auth type '" + profile.getType() + "'", false);
                }
            } catch (SecretResolver.MissingSecretException e) {
                throw new TaskFailure("HTTP_CONFIG", e.getMessage(), false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while fetching a token", e);
            }
            return uri;
        }
    }

    private HttpClient clientFor(String connectionName, HttpWorkerProperties.Connection connection) {
        var key = connectionName == null ? "" : connectionName;
        var connectTimeout = connection == null ? properties.getConnectTimeout() : connection.getConnectTimeout();
        return clients.computeIfAbsent(key, k -> HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    private String readCapped(InputStream body) throws IOException {
        try (body) {
            var bytes = body.readNBytes((int) Math.min(Integer.MAX_VALUE - 1, properties.getMaxResponseBytes() + 1));
            if (bytes.length > properties.getMaxResponseBytes()) {
                throw new TaskFailure("HTTP_RESPONSE_TOO_LARGE", "the response is larger than "
                        + properties.getMaxResponseBytes() + " bytes (workflow.http.max-response-bytes)", false);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static boolean isSuccess(int status, JsonNode successStatus) {
        if (successStatus.isArray() && !successStatus.isEmpty()) {
            for (var code : successStatus) {
                if (code.asInt() == status) return true;
            }
            return false;
        }
        return status / 100 == 2;
    }

    static boolean retryable(int status, List<String> retryOn) {
        return retryOn.contains(String.valueOf(status))
                || (status / 100 == 5 && retryOn.contains("5xx"))
                || (status / 100 == 4 && retryOn.contains("4xx"));
    }

    private static TaskFailure failure(String code, String reason, boolean retryable) {
        return new TaskFailure(code, reason, retryable);
    }

    private Map<String, Object> outputs(JsonNode output, int status, Map<String, List<String>> headers, String body) {
        var result = new LinkedHashMap<String, Object>();
        if (!output.isObject()) {
            return result;
        }
        var context = new LinkedHashMap<String, Object>();
        context.put("status", status);
        var flatHeaders = new LinkedHashMap<String, String>();
        headers.forEach((name, values) -> flatHeaders.put(name.toLowerCase(), values.isEmpty() ? null : values.getFirst()));
        context.put("headers", flatHeaders);
        context.put("body", parsed(body));
        output.properties().forEach(entry -> {
            try {
                result.put(entry.getKey(), Templates.render("${" + entry.getValue().asText() + "}", context));
            } catch (Templates.TemplateException e) {
                throw new TaskFailure("HTTP_OUTPUT", "output '" + entry.getKey() + "': " + e.getMessage(), false);
            }
        });
        return result;
    }

    private static Object parsed(String body) {
        var trimmed = body == null ? "" : body.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return JSON.readValue(trimmed, Object.class);
            } catch (IOException notJson) {
                return body;
            }
        }
        return body;
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /** The URL for logs and reasons, without its query string (which may carry personal data or keys). */
    static String redacted(URI uri) {
        var text = uri.toString();
        var q = text.indexOf('?');
        return q < 0 ? text : text.substring(0, q) + "?…";
    }
}
