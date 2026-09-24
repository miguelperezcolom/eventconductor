package io.mateu.workflow.worker.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.worker.FailureMarkers;
import io.mateu.workflow.worker.api.Cancellations;
import io.mateu.workflow.worker.api.TaskDispatcher;
import io.mateu.workflow.worker.api.TaskFailure;
import io.mateu.workflow.worker.api.TaskRegistration;
import io.mateu.workflow.worker.api.TaskRegistry;
import io.mateu.workflow.worker.api.TaskReplySink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpCallHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    HttpServer server;
    String base;
    final List<Map<String, Object>> seen = new CopyOnWriteArrayList<>();
    final Map<String, Responder> routes = new ConcurrentHashMap<>();
    final AtomicInteger tokenCalls = new AtomicInteger();
    HttpWorkerProperties properties;
    HttpCallHandler handler;

    interface Responder {
        void respond(HttpExchange exchange, String body) throws IOException;
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(Map.of("method", exchange.getRequestMethod(), "uri", exchange.getRequestURI().toString(),
                    "headers", exchange.getRequestHeaders(), "body", body));
            var responder = routes.getOrDefault(exchange.getRequestURI().getPath(), (e, b) -> reply(e, 404, "{\"error\":\"nope\"}"));
            responder.respond(exchange, body);
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        properties = new HttpWorkerProperties();
        var connection = new HttpWorkerProperties.Connection();
        connection.setBaseUrl(base + "/api");
        connection.setHeaders(Map.of("X-Client", "ec"));
        connection.setReadTimeout(Duration.ofSeconds(2));
        properties.getConnections().put("local", connection);
        properties.getSecrets().put("TOKEN", "t0k3n");
        properties.getSecrets().put("PASS", "s3cret");
        handler = handler(List.of());
    }

    private HttpCallHandler handler(List<String> allowedHosts) {
        return new HttpCallHandler(properties, new SecretResolver(properties.getSecrets()::get),
                new HostGuard(allowedHosts, InetAddress::getAllByName), null);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    static void reply(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("X-Trace", "abc");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static ObjectNode request(String json) throws IOException {
        return (ObjectNode) JSON.readTree(json);
    }

    private Map<String, Object> call(String json) throws Exception {
        return handler.handle(new HttpCallHandler.Input(request(json)), null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> lastHeaders() {
        return (Map<String, List<String>>) seen.getLast().get("headers");
    }

    @Test
    void postsJsonThroughAConnectionAndMapsTheResponse() throws Exception {
        routes.put("/api/charges/B-1", (e, b) -> reply(e, 201, "{\"id\":\"ch_1\",\"amount\":12.5,\"items\":[1,2]}"));

        var out = call("""
                {"method":"POST","connection":"local","path":"/charges/B-1","query":{"currency":"EUR €"},
                 "headers":{"X-Tenant":"acme"},"body":"{\\"amount\\":12.5}","bodyKind":"json",
                 "output":{"chargeId":"body.id","amount":"body.amount","status":"status","trace":"headers['x-trace']",
                           "count":"size(body.items)"},
                 "idempotencyKey":"se-1"}
                """);

        assertThat(out).containsEntry("chargeId", "ch_1").containsEntry("amount", 12.5).containsEntry("status", 201)
                .containsEntry("trace", "abc").containsEntry("count", 2);
        var hit = seen.getLast();
        assertThat(hit.get("method")).isEqualTo("POST");
        assertThat(hit.get("uri")).isEqualTo("/api/charges/B-1?currency=EUR+%E2%82%AC");
        assertThat(hit.get("body")).isEqualTo("{\"amount\":12.5}");
        assertThat(lastHeaders().get("X-tenant")).containsExactly("acme");
        assertThat(lastHeaders().get("X-client")).containsExactly("ec");
        assertThat(lastHeaders().get("Idempotency-key")).containsExactly("se-1");
        assertThat(lastHeaders().get("Content-type")).containsExactly("application/json");
    }

    @Test
    void aClientErrorIsFinalAndAServerErrorIsRetryable() throws Exception {
        routes.put("/api/bad", (e, b) -> reply(e, 422, "{\"error\":\"invalid\"}"));
        routes.put("/api/down", (e, b) -> reply(e, 503, ""));

        assertThatThrownBy(() -> call("{\"connection\":\"local\",\"path\":\"/bad\"}"))
                .isInstanceOfSatisfying(TaskFailure.class, f -> {
                    assertThat(f.code()).isEqualTo("HTTP_422");
                    assertThat(f.retryable()).isFalse();
                    assertThat(f.reason()).startsWith(FailureMarkers.NON_RETRYABLE).contains("invalid");
                });
        assertThatThrownBy(() -> call("{\"connection\":\"local\",\"path\":\"/down\"}"))
                .isInstanceOfSatisfying(TaskFailure.class, f -> assertThat(f.retryable()).isTrue());
        assertThatThrownBy(() -> call("{\"connection\":\"local\",\"path\":\"/bad\",\"retryOn\":[\"422\"]}"))
                .isInstanceOfSatisfying(TaskFailure.class, f -> assertThat(f.retryable()).isTrue());
        assertThatThrownBy(() -> call("{\"connection\":\"local\",\"path\":\"/down\",\"retryOn\":[\"4xx\"]}"))
                .isInstanceOfSatisfying(TaskFailure.class, f -> assertThat(f.retryable()).isFalse());
    }

    @Test
    void successStatusDecidesWhatCompletes() throws Exception {
        routes.put("/api/gone", (e, b) -> reply(e, 404, ""));
        assertThat(call("{\"connection\":\"local\",\"path\":\"/gone\",\"successStatus\":[404],\"output\":{\"s\":\"status\"}}"))
                .containsEntry("s", 404);
    }

    @Test
    void aTimeoutIsAnIoFailure() {
        routes.put("/api/slow", (e, b) -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            reply(e, 200, "{}");
        });
        assertThatThrownBy(() -> call("{\"connection\":\"local\",\"path\":\"/slow\"}"))
                .isInstanceOfSatisfying(TaskFailure.class, f -> {
                    assertThat(f.code()).isEqualTo("HTTP_IO");
                    assertThat(f.retryable()).isTrue();
                });
    }

    @Test
    void basicBearerAndApiKeyAuthUseResolvedSecrets() throws Exception {
        routes.put("/api/x", (e, b) -> reply(e, 200, "{}"));
        var basic = new HttpWorkerProperties.AuthProfile();
        basic.setType("basic");
        basic.setUsername("svc");
        basic.setPassword("${secret:PASS}");
        properties.getAuth().put("basic", basic);
        call("{\"connection\":\"local\",\"path\":\"/x\",\"auth\":\"basic\"}");
        assertThat(lastHeaders().get("Authorization")).containsExactly("Basic " +
                java.util.Base64.getEncoder().encodeToString("svc:s3cret".getBytes()));

        call("{\"connection\":\"local\",\"path\":\"/x\",\"auth\":{\"type\":\"bearer\",\"token\":\"${secret:TOKEN}\"}}");
        assertThat(lastHeaders().get("Authorization")).containsExactly("Bearer t0k3n");

        call("{\"connection\":\"local\",\"path\":\"/x\",\"auth\":{\"type\":\"api-key\",\"header\":\"X-Key\",\"value\":\"${secret:TOKEN}\"}}");
        assertThat(lastHeaders().get("X-key")).containsExactly("t0k3n");

        call("{\"connection\":\"local\",\"path\":\"/x\",\"auth\":{\"type\":\"api-key\",\"in\":\"query\",\"header\":\"key\",\"value\":\"${secret:TOKEN}\"}}");
        assertThat(seen.getLast().get("uri")).isEqualTo("/api/x?key=t0k3n");

        assertThatThrownBy(() -> call("{\"connection\":\"local\",\"path\":\"/x\",\"auth\":{\"type\":\"bearer\",\"token\":\"${secret:MISSING}\"}}"))
                .isInstanceOfSatisfying(TaskFailure.class, f -> assertThat(f.code()).isEqualTo("HTTP_CONFIG"));
    }

    @Test
    void theConnectionsDefaultAuthIsUsedAndOAuthTokensAreCachedAndRefreshedOn401() throws Exception {
        routes.put("/token", (e, b) -> {
            tokenCalls.incrementAndGet();
            assertThat(b).contains("grant_type=client_credentials").contains("client_secret=s3cret");
            reply(e, 200, "{\"access_token\":\"tok-" + tokenCalls.get() + "\",\"expires_in\":3600}");
        });
        var rejectOnce = new AtomicInteger();
        routes.put("/api/secure", (e, b) -> {
            var auth = e.getRequestHeaders().getFirst("Authorization");
            if ("Bearer tok-1".equals(auth) && rejectOnce.getAndIncrement() == 1) {
                reply(e, 401, "");
            } else {
                reply(e, 200, "{\"auth\":\"" + auth + "\"}");
            }
        });
        var oauth = new HttpWorkerProperties.AuthProfile();
        oauth.setType("oauth2-client-credentials");
        oauth.setTokenUri(base + "/token");
        oauth.setClientId("ec");
        oauth.setClientSecret("${secret:PASS}");
        properties.getAuth().put("idp", oauth);
        properties.getConnections().get("local").setAuth("idp");

        assertThat(call("{\"connection\":\"local\",\"path\":\"/secure\",\"output\":{\"a\":\"body.auth\"}}")).containsEntry("a", "Bearer tok-1");
        assertThat(tokenCalls.get()).isEqualTo(1);
        // Second call: the cached token is rejected once → a fresh token, one retry.
        assertThat(call("{\"connection\":\"local\",\"path\":\"/secure\",\"output\":{\"a\":\"body.auth\"}}")).containsEntry("a", "Bearer tok-2");
        assertThat(tokenCalls.get()).isEqualTo(2);
    }

    @Test
    void anAbsoluteUrlToAnInternalAddressIsRefusedAndAnUnknownConnectionIsAConfigError() {
        assertThatThrownBy(() -> call("{\"url\":\"" + base + "/api/x\"}"))
                .isInstanceOfSatisfying(TaskFailure.class, f -> {
                    assertThat(f.code()).isEqualTo("HTTP_FORBIDDEN_HOST");
                    assertThat(f.retryable()).isFalse();
                });
        assertThatThrownBy(() -> call("{\"connection\":\"nope\"}"))
                .isInstanceOfSatisfying(TaskFailure.class, f -> assertThat(f.code()).isEqualTo("HTTP_CONFIG"));
        assertThatThrownBy(() -> call("{\"connection\":\"local\",\"auth\":\"ghost\"}"))
                .isInstanceOfSatisfying(TaskFailure.class, f -> assertThat(f.code()).isEqualTo("HTTP_CONFIG"));
        assertThatThrownBy(() -> handler.handle(new HttpCallHandler.Input(null), null))
                .isInstanceOfSatisfying(TaskFailure.class, f -> assertThat(f.code()).isEqualTo("HTTP_REQUEST"));
    }

    @Test
    void anOversizedResponseFailsAndATextBodyKeepsItsType() throws Exception {
        properties.setMaxResponseBytes(10);
        routes.put("/api/big", (e, b) -> reply(e, 200, "{\"much\":\"too much\"}"));
        assertThatThrownBy(() -> call("{\"connection\":\"local\",\"path\":\"/big\"}"))
                .isInstanceOfSatisfying(TaskFailure.class, f -> assertThat(f.code()).isEqualTo("HTTP_RESPONSE_TOO_LARGE"));
        properties.setMaxResponseBytes(1000);
        routes.put("/api/xml", (e, b) -> reply(e, 200, "<ok/>"));
        assertThat(call("{\"method\":\"PUT\",\"connection\":\"local\",\"path\":\"xml\",\"body\":\"<a/>\",\"bodyKind\":\"text\",\"output\":{\"raw\":\"body\"}}"))
                .containsEntry("raw", "<ok/>");
        assertThat(lastHeaders().get("Content-type")).containsExactly("text/plain; charset=utf-8");
        assertThat(HttpCallHandler.redacted(java.net.URI.create("https://x/y?key=secret"))).isEqualTo("https://x/y?…");
    }

    @Test
    void throughTheTaskDispatcherTheRenderedVariableBindsAndOutputsBecomeVariables() throws Exception {
        routes.put("/api/r", (e, b) -> reply(e, 200, "{\"id\":\"R-1\",\"n\":3}"));
        TaskRegistration<?, ?> registration = new HttpWorkerAutoConfiguration().httpCallTaskRegistration(handler, properties);
        assertThat(registration.ref()).isEqualTo("http-call@1");
        assertThat(registration.topic()).isEqualTo("http-calls");
        var completed = new ArrayList<List<Variable>>();
        var failed = new ArrayList<String>();
        var sink = new TaskReplySink() {
            public void running(TaskExecutionRequested task) { }
            public void completed(TaskExecutionRequested task, List<Variable> variables) { completed.add(variables); }
            public void failed(TaskExecutionRequested task, List<Variable> variables, String reason) { failed.add(reason); }
        };
        var dispatcher = new TaskDispatcher(new TaskRegistry(List.of(registration)), sink,
                Cancellations.NONE, JSON, true);
        dispatcher.dispatch(new TaskExecutionRequested("se-9", "p", "wd", "call", "http-call@1", List.of(
                new Variable("bookingId", "B-1"), new Variable("amount", "12.5"),
                new Variable("__http", "{\"connection\":\"local\",\"path\":\"/r\",\"output\":{\"rid\":\"body.id\",\"n\":\"body.n\"}}"))));
        assertThat(failed).isEmpty();
        assertThat(completed.getFirst()).extracting(Variable::name, Variable::value)
                .contains(org.assertj.core.groups.Tuple.tuple("rid", "R-1"), org.assertj.core.groups.Tuple.tuple("n", "3"));
    }
}
