package io.mateu.workflow.test;

import com.fasterxml.jackson.databind.JsonNode;
import io.mateu.workflow.template.Templates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * The responses an {@code HTTP_CALL} step gets instead of the network. The engine renders the
 * request as it always does; the kit records it ({@link #requests()}) and answers it with the next
 * response here, applying the step's {@code successStatus}, {@code retryOn} and {@code output}
 * mapping the way the built-in {@code http-call} task does — so a 503 is retried, a 404 fails the
 * step at once, and {@code output: { chargeId: body.id }} reads the stubbed body.
 *
 * <p>Answers are consumed in order and the last one repeats, as for {@link TaskStub}.
 *
 * <p>A call can be answered apart from the others — a forEach iteration, an item, a request — by a
 * <b>scoped</b> stub: {@link #whenIteration(int)}, {@link #whenItem(Predicate)} or
 * {@link #whenRequest(Predicate)} return one with answers of its own (consumed, and repeated, on their
 * own), and the first scope that matches a call answers it; a call no scope matches gets this stub's
 * answers.
 *
 * <pre>{@code
 * var hotel = runtime.whenHttpCall("rooms").thenRespond(201, "{\"id\": \"H-1\"}");   // every room…
 * hotel.whenIteration(1).thenRespond(409, "sold out");                              // …but the second
 * }</pre>
 */
public final class HttpStub {

    /** A stubbed response; {@code ioError} stands for a connection that failed before any status. */
    public record Response(int status, String body, Map<String, String> headers, boolean ioError) {
    }

    /** The forEach iteration a call belongs to: its 0-based index and its item (parsed: a map, a list, text …). */
    record Iteration(int index, Object item) {
    }

    private record Scope(Predicate<Map<String, Object>> request, Predicate<Iteration> iteration, HttpStub stub) {
        boolean matches(Map<String, Object> rendered, Iteration call) {
            return iteration != null ? call != null && iteration.test(call) : request.test(rendered);
        }
    }

    private final String stepId;
    private final String description;
    private final List<Scope> scopes = new CopyOnWriteArrayList<>();
    private final List<Response> responses = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    HttpStub(String stepId) {
        this(stepId, "HTTP_CALL step '" + stepId + "'");
    }

    private HttpStub(String stepId, String description) {
        this.stepId = stepId;
        this.description = description;
    }

    /**
     * A stub for the calls of one forEach iteration ({@code 0} = the first item) of this step, with answers
     * of its own. Applies to an HTTP_CALL that has the {@code forEach} itself; inside an inline PROCESS
     * body, match the request instead ({@link #whenRequest}).
     */
    public HttpStub whenIteration(int index) {
        return scope(null, call -> call.index() == index, " (iteration #" + index + ")");
    }

    /**
     * A stub for the calls of the forEach iterations whose item matches — the item parsed: a map for a
     * JSON object, a list, text or a number. {@code whenItem(room -> ((Map<?, ?>) room).get("number").equals("900"))}.
     */
    public HttpStub whenItem(Predicate<Object> item) {
        return scope(null, call -> item.test(call.item()), " (an item)");
    }

    /**
     * A stub for the calls of the forEach iterations whose item is an object with {@code field} =
     * {@code value} (compared as text): {@code whenItemHas("number", "900")}.
     */
    public HttpStub whenItemHas(String field, Object value) {
        return scope(null, call -> call.item() instanceof Map<?, ?> item && item.get(field) != null
                && Values.text(item.get(field)).equals(Values.text(value)), " (" + field + "=" + Values.text(value) + ")");
    }

    /**
     * A stub for the calls whose rendered request matches ({@code method}, {@code url} or
     * {@code connection} + {@code path}, {@code headers}, {@code body} …, as {@link #requests()} lists them).
     */
    public HttpStub whenRequest(Predicate<Map<String, Object>> request) {
        return scope(request, null, " (a request)");
    }

    private HttpStub scope(Predicate<Map<String, Object>> request, Predicate<Iteration> iteration, String what) {
        var scoped = new HttpStub(stepId, description + what);
        scopes.add(new Scope(request, iteration, scoped));
        return scoped;
    }

    /** Answers with {@code status} and an empty body. */
    public HttpStub thenRespond(int status) {
        return thenRespond(status, "", Map.of());
    }

    /** Answers with {@code status} and {@code body} (JSON bodies are parsed for the output mapping). */
    public HttpStub thenRespond(int status, String body) {
        return thenRespond(status, body, Map.of());
    }

    /** Answers with {@code status}, {@code body} and these response headers. */
    public HttpStub thenRespond(int status, String body, Map<String, String> headers) {
        var lower = new LinkedHashMap<String, String>();
        headers.forEach((name, value) -> lower.put(name.toLowerCase(), value));
        responses.add(new Response(status, body == null ? "" : body, lower, false));
        return this;
    }

    /** Fails as an I/O error would (connection refused, timed out): retried when {@code retryOn} has {@code io}. */
    public HttpStub thenIoError() {
        responses.add(new Response(0, "", Map.of(), true));
        return this;
    }

    /** Every request the step rendered, in order: {@code method}, {@code url}/{@code connection}+{@code path}, {@code headers}, {@code body} … */
    public List<Map<String, Object>> requests() {
        return List.copyOf(requests);
    }

    /** The last request rendered, or null. */
    public Map<String, Object> lastRequest() {
        return requests.isEmpty() ? null : requests.getLast();
    }

    TaskOutcome answer(JsonNode request) {
        return answer(request, null);
    }

    /** Answers a call — of forEach iteration {@code iteration}, null for none — by the first scope it matches, else by this stub. */
    TaskOutcome answer(JsonNode request, Iteration iteration) {
        var rendered = Values.toMap(request);
        requests.add(rendered);
        for (var scope : scopes) {
            if (scope.matches(rendered, iteration)) {
                return scope.stub().answer(request, iteration);
            }
        }
        if (responses.isEmpty()) {
            return TaskOutcome.fail("HTTP_STUB", description + " is stubbed but has no response"
                    + (iteration == null ? "" : " for iteration #" + iteration.index()), false);
        }
        var response = responses.get(Math.min(calls.getAndIncrement(), responses.size() - 1));
        var retryOn = new ArrayList<String>();
        request.path("retryOn").forEach(node -> retryOn.add(node.asText()));
        if (retryOn.isEmpty() && request.path("retryOn").isMissingNode()) {
            retryOn.addAll(List.of("5xx", "io"));
        }
        var method = request.path("method").asText("GET");
        var target = request.hasNonNull("url") ? request.path("url").asText()
                : request.path("connection").asText("") + request.path("path").asText("");
        if (response.ioError()) {
            return TaskOutcome.fail("HTTP_IO", method + " " + target + " failed: stubbed I/O error", retryOn.contains("io"));
        }
        var status = response.status();
        if (!isSuccess(status, request.path("successStatus"))) {
            var body = response.body();
            return TaskOutcome.fail("HTTP_" + status, method + " " + target + " answered " + status
                    + (body.isBlank() ? "" : ": " + body), retryable(status, retryOn));
        }
        var output = request.path("output");
        var result = new LinkedHashMap<String, Object>();
        if (output.isObject()) {
            var context = new LinkedHashMap<String, Object>();
            context.put("status", status);
            context.put("headers", response.headers());
            context.put("body", Values.parsed(response.body()));
            for (var entry : output.properties()) {
                try {
                    result.put(entry.getKey(), Templates.render("${" + entry.getValue().asText() + "}", context));
                } catch (Templates.TemplateException e) {
                    return TaskOutcome.fail("HTTP_OUTPUT", "output '" + entry.getKey() + "': " + e.getMessage(), false);
                }
            }
        }
        return TaskOutcome.complete(result);
    }

    private static boolean isSuccess(int status, JsonNode successStatus) {
        if (successStatus.isArray() && !successStatus.isEmpty()) {
            for (var code : successStatus) {
                if (code.asInt() == status) {
                    return true;
                }
            }
            return false;
        }
        return status / 100 == 2;
    }

    private static boolean retryable(int status, List<String> retryOn) {
        return retryOn.contains(String.valueOf(status))
                || (status / 100 == 5 && retryOn.contains("5xx"))
                || (status / 100 == 4 && retryOn.contains("4xx"));
    }
}
