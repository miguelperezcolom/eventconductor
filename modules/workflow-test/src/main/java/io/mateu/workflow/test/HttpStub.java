package io.mateu.workflow.test;

import com.fasterxml.jackson.databind.JsonNode;
import io.mateu.workflow.template.Templates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The responses an {@code HTTP_CALL} step gets instead of the network. The engine renders the
 * request as it always does; the kit records it ({@link #requests()}) and answers it with the next
 * response here, applying the step's {@code successStatus}, {@code retryOn} and {@code output}
 * mapping the way the built-in {@code http-call} task does — so a 503 is retried, a 404 fails the
 * step at once, and {@code output: { chargeId: body.id }} reads the stubbed body.
 *
 * <p>Answers are consumed in order and the last one repeats, as for {@link TaskStub}.
 */
public final class HttpStub {

    /** A stubbed response; {@code ioError} stands for a connection that failed before any status. */
    public record Response(int status, String body, Map<String, String> headers, boolean ioError) {
    }

    private final String stepId;
    private final List<Response> responses = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    HttpStub(String stepId) {
        this.stepId = stepId;
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
        requests.add(Values.toMap(request));
        if (responses.isEmpty()) {
            return TaskOutcome.fail("HTTP_STUB", "HTTP_CALL step '" + stepId + "' is stubbed but has no response", false);
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
