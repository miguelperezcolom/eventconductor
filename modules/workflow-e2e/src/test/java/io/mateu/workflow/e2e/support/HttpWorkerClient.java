package io.mateu.workflow.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.httpworkers.HttpTaskService;
import io.mateu.workflow.application.httpworkers.HttpTaskWaiters;
import io.mateu.workflow.infra.config.HttpWorkersProperties;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.infra.in.rest.WorkerApiController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A worker that talks to the engine only through the HTTP worker API — dispatcher, async long-poll,
 * JSON, status codes — over the real engine the e2e context booted, without a servlet container.
 * What a Python or Go worker does, written in Java.
 */
public class HttpWorkerClient {

    public static final String KEY = "e2e-worker-key";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MockMvc mvc;

    public HttpWorkerClient(HttpTaskService service, HttpTaskWaiters waiters, HttpWorkersProperties properties) {
        properties.setApiKeys(new java.util.LinkedHashMap<>(Map.of("e2e", KEY)));
        this.mvc = MockMvcBuilders.standaloneSetup(
                new WorkerApiController(service, waiters, properties, new MessageApiProperties())).build();
    }

    /** Long-polls for tasks of {@code topic}; the activation's JSON. */
    public JsonNode activate(String workerId, String topic, int maxTasks, long leaseMs, long waitMs) throws Exception {
        var result = call("/activate", Map.of("workerId", workerId, "topics", java.util.List.of(topic),
                "maxTasks", maxTasks, "leaseMs", leaseMs, "waitMs", waitMs));
        if (result.getResponse().getStatus() != 200) {
            throw new IllegalStateException("activate answered " + result.getResponse().getStatus());
        }
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    /** The first task an activation returns, waiting up to {@code waitMs}; fails if none. */
    public JsonNode next(String workerId, String topic, long waitMs) throws Exception {
        var tasks = activate(workerId, topic, 1, 30_000, waitMs).path("tasks");
        if (tasks.isEmpty()) {
            throw new IllegalStateException("No task on " + topic + " within " + waitMs + " ms");
        }
        return tasks.get(0);
    }

    public int complete(JsonNode task, Map<String, Object> variables) throws Exception {
        return call("/tasks/" + task.path("id").asText() + "/complete",
                Map.of("attempt", task.path("attempt").asInt(), "variables", variables)).getResponse().getStatus();
    }

    public MvcResult completeResult(JsonNode task, Map<String, Object> variables) throws Exception {
        return call("/tasks/" + task.path("id").asText() + "/complete",
                Map.of("attempt", task.path("attempt").asInt(), "variables", variables));
    }

    public int completeWithAttempt(String id, int attempt) throws Exception {
        return call("/tasks/" + id + "/complete", Map.of("attempt", attempt, "variables", Map.of()))
                .getResponse().getStatus();
    }

    public int fail(JsonNode task, String code, String message, boolean retryable) throws Exception {
        return call("/tasks/" + task.path("id").asText() + "/fail", Map.of("attempt", task.path("attempt").asInt(),
                "code", code, "message", message, "retryable", retryable)).getResponse().getStatus();
    }

    public MvcResult heartbeat(JsonNode task, long extendMs) throws Exception {
        return call("/tasks/" + task.path("id").asText() + "/heartbeat",
                Map.of("leaseToken", task.path("leaseToken").asText(), "extendMs", extendMs));
    }

    public int inject(JsonNode task, Object steps) throws Exception {
        return call("/tasks/" + task.path("id").asText() + "/inject",
                Map.of("attempt", task.path("attempt").asInt(), "steps", steps)).getResponse().getStatus();
    }

    public static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult call(String path, Object body) throws Exception {
        var first = mvc.perform(post("/workflow/api/workers" + path).contentType("application/json")
                .header("X-Api-Key", KEY).content(JSON.writeValueAsString(body))).andReturn();
        if (first.getRequest().isAsyncStarted()) {
            first.getAsyncResult(60_000);
            return mvc.perform(asyncDispatch(first)).andReturn();
        }
        return first;
    }
}
