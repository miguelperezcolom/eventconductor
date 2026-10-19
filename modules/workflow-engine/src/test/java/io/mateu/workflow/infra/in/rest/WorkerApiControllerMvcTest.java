package io.mateu.workflow.infra.in.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.httpworkers.HttpTaskInbox;
import io.mateu.workflow.application.httpworkers.HttpTaskService;
import io.mateu.workflow.application.httpworkers.HttpTaskSignal;
import io.mateu.workflow.application.httpworkers.HttpTaskWaiters;
import io.mateu.workflow.application.httpworkers.HttpWorkerMetrics;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.StepsInjected;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.workflow.infra.config.HttpWorkersProperties;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.infra.out.memory.InMemoryHttpTaskQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Every worker endpoint through the dispatcher, over the real service and an in-memory queue. */
class WorkerApiControllerMvcTest {

    static final String WORKER_KEY = "worker-secret";
    static final String ENGINE_KEY = "engine-secret";
    static final ObjectMapper JSON = new ObjectMapper();

    InMemoryHttpTaskQueue queue = new InMemoryHttpTaskQueue();
    List<DomainEvent> published = new ArrayList<>();
    boolean engineDown;
    HttpTaskSignal signal = new HttpTaskSignal();
    HttpTaskInbox inbox = new HttpTaskInbox(queue, signal, HttpWorkerMetrics.none(), Clock.systemUTC());
    HttpTaskService service = new HttpTaskService(queue, event -> {
        if (engineDown) {
            throw new IllegalStateException("down");
        }
        published.add(event);
    }, HttpWorkerMetrics.none(), Clock.systemUTC());
    HttpWorkersProperties properties = new HttpWorkersProperties();
    MessageApiProperties apiProperties = new MessageApiProperties();
    HttpTaskWaiters waiters;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        properties.setApiKeys(new java.util.LinkedHashMap<>(Map.of("python", WORKER_KEY, "blank", " ")));
        properties.setMaxWaiting(2);
        properties.setMaxWait(Duration.ofSeconds(2));
        apiProperties.setApiKey(ENGINE_KEY);
        waiters = new HttpTaskWaiters(service, signal, HttpWorkerMetrics.none(),
                new HttpTaskWaiters.Settings(properties.getMaxWaiting(), Duration.ofMillis(100)));
        waiters.start();
        mvc = MockMvcBuilders.standaloneSetup(new WorkerApiController(service, waiters, properties, apiProperties))
                .build();
    }

    @AfterEach
    void tearDown() {
        waiters.stop();
    }

    private void dispatch(String id, int attempt) {
        inbox.dispatched(new TaskExecutionRequested(id, "p-" + id, "wd", "charge", "charge@1",
                List.of(new Variable("amount", "42")), attempt), "payments");
    }

    private MvcResult call(String path, String key, String body) throws Exception {
        var request = post("/workflow/api/workers" + path).contentType("application/json").content(body);
        if (key != null) {
            request = request.header("X-Api-Key", key);
        }
        var first = mvc.perform(request).andReturn();
        if (first.getRequest().isAsyncStarted()) {
            first.getAsyncResult(10_000);
            return mvc.perform(asyncDispatch(first)).andReturn();
        }
        return first;
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode activate(String key, String workerId, long waitMs) throws Exception {
        var result = call("/activate", key, "{\"workerId\": \"" + workerId + "\", \"topics\": [\"payments\", \" \"],"
                + " \"maxTasks\": 10, \"leaseMs\": 30000, \"waitMs\": " + waitMs + "}");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json(result);
    }

    @Test
    void activateHandsOutTasksWithEverythingAWorkerNeeds() throws Exception {
        dispatch("se-1", 2);

        var body = activate(WORKER_KEY, "py-1", 1_000);

        var task = body.path("tasks").get(0);
        assertThat(task.path("id").asText()).isEqualTo("se-1");
        assertThat(task.path("processId").asText()).isEqualTo("p-se-1");
        assertThat(task.path("workflowDefinitionId").asText()).isEqualTo("wd");
        assertThat(task.path("stepId").asText()).isEqualTo("charge");
        assertThat(task.path("taskId").asText()).isEqualTo("charge@1");
        assertThat(task.path("topic").asText()).isEqualTo("payments");
        assertThat(task.path("variables").path("amount").asText()).isEqualTo("42");
        assertThat(task.path("attempt").asInt()).isEqualTo(2);
        assertThat(task.path("delivery").asInt()).isEqualTo(1);
        assertThat(task.path("leaseToken").asText()).isNotBlank();
        var deadline = Instant.parse(task.path("deadline").asText());
        assertThat(deadline).isBetween(Instant.now().plusSeconds(20), Instant.now().plusSeconds(31));
    }

    @Test
    void anActivationThatFindsNothingAnswersAnEmptyList() throws Exception {
        assertThat(activate(WORKER_KEY, "py-1", 200).path("tasks")).isEmpty();
        assertThat(activate(WORKER_KEY, "py-1", 0).path("tasks")).isEmpty();
    }

    @Test
    void aMalformedActivationIsRefused() throws Exception {
        assertThat(call("/activate", WORKER_KEY, "{\"topics\": [\"payments\"]}").getResponse().getStatus())
                .isEqualTo(400);
        assertThat(call("/activate", WORKER_KEY, "{\"workerId\": \"w\", \"topics\": [\" \"]}").getResponse().getStatus())
                .isEqualTo(400);
        assertThat(call("/activate", WORKER_KEY, "{\"workerId\": \"w\"}").getResponse().getStatus()).isEqualTo(400);
        var tooLong = "x".repeat(300);
        assertThat(call("/activate", WORKER_KEY, "{\"workerId\": \"" + tooLong + "\", \"topics\": [\"payments\"]}")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(post("/workflow/api/workers/activate").header("X-Api-Key", WORKER_KEY))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void pastMaxWaitingAnActivationGets429WithRetryAfter() throws Exception {
        waiters.activate("a", java.util.Set.of("payments"), 1, Duration.ofSeconds(5), Duration.ofSeconds(5));
        waiters.activate("b", java.util.Set.of("payments"), 1, Duration.ofSeconds(5), Duration.ofSeconds(5));

        var result = call("/activate", WORKER_KEY, "{\"workerId\": \"c\", \"topics\": [\"payments\"], \"waitMs\": 100}");

        assertThat(result.getResponse().getStatus()).isEqualTo(429);
        assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("1");
        assertThat(json(result).path("reason").asText()).isEqualTo("OVERLOADED");
    }

    @Test
    void completeAnswers204AndTheEngineGetsTheVariables() throws Exception {
        dispatch("se-1", 1);
        activate(WORKER_KEY, "py-1", 0);

        var result = call("/tasks/se-1/complete", WORKER_KEY,
                "{\"attempt\": 1, \"variables\": {\"chargeId\": \"ch-1\", \"total\": 10, \"ok\": true}}");

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        var reply = (TaskStatusChanged) published.get(0);
        assertThat(reply.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(reply.variables()).containsExactly(new Variable("chargeId", "ch-1"), new Variable("total", "10"),
                new Variable("ok", "true"));
    }

    @Test
    void aReplyForAGoneTaskIs404AndForAReplacedDispatch409() throws Exception {
        assertThat(call("/tasks/nope/complete", WORKER_KEY, "{\"attempt\": 1}").getResponse().getStatus())
                .isEqualTo(404);

        dispatch("se-1", 1);
        activate(WORKER_KEY, "py-1", 0);
        dispatch("se-1", 2);
        var stale = call("/tasks/se-1/complete", WORKER_KEY, "{\"attempt\": 1, \"variables\": {}}");
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(stale).path("reason").asText()).isEqualTo("STALE_ATTEMPT");
        assertThat(published).isEmpty();

        assertThat(call("/tasks/se-1/complete", WORKER_KEY, "{}").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void failCarriesTheCodeMessageAndRetryability() throws Exception {
        dispatch("se-1", 1);
        activate(WORKER_KEY, "py-1", 0);

        var result = call("/tasks/se-1/fail", WORKER_KEY,
                "{\"attempt\": 1, \"code\": \"CARD_DECLINED\", \"message\": \"no funds\", \"retryable\": false}");

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(((TaskLogEmitted) published.get(0)).message()).isEqualTo("[non-retryable] CARD_DECLINED: no funds");
        assertThat(((TaskStatusChanged) published.get(1)).nonRetryable()).isTrue();

        dispatch("se-2", 1);
        activate(WORKER_KEY, "py-1", 0);
        assertThat(call("/tasks/se-2/fail", WORKER_KEY, "{\"attempt\": 1}").getResponse().getStatus()).isEqualTo(204);
        assertThat(((TaskStatusChanged) published.get(3)).nonRetryable()).isFalse();
    }

    @Test
    void aHeartbeatExtendsTheLeaseAndCarriesTheCancellation() throws Exception {
        dispatch("se-1", 1);
        var token = activate(WORKER_KEY, "py-1", 0).path("tasks").get(0).path("leaseToken").asText();

        var beat = call("/tasks/se-1/heartbeat", WORKER_KEY, "{\"leaseToken\": \"" + token + "\", \"extendMs\": 60000}");
        assertThat(beat.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(beat).path("cancelled").asBoolean()).isFalse();
        assertThat(Instant.parse(json(beat).path("deadline").asText())).isAfter(Instant.now().plusSeconds(50));

        var lost = call("/tasks/se-1/heartbeat", WORKER_KEY, "{\"leaseToken\": \"other\"}");
        assertThat(lost.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(lost).path("reason").asText()).isEqualTo("LEASE_LOST");

        inbox.cancelled("se-1");
        var cancelled = call("/tasks/se-1/heartbeat", WORKER_KEY, "{\"leaseToken\": \"" + token + "\"}");
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(cancelled).path("cancelled").asBoolean()).isTrue();

        assertThat(call("/tasks/se-1/heartbeat", WORKER_KEY, "{\"leaseToken\": \"" + token + "\"}")
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(call("/tasks/se-1/heartbeat", WORKER_KEY, "{}").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void injectSendsTheStepsToTheEngine() throws Exception {
        dispatch("se-1", 1);
        activate(WORKER_KEY, "py-1", 0);

        var result = call("/tasks/se-1/inject", WORKER_KEY,
                "{\"attempt\": 1, \"steps\": [{\"id\": \"extra\", \"type\": \"ACTION\"}]}");
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(((StepsInjected) published.get(0)).stepsJson()).contains("\"id\":\"extra\"");

        assertThat(call("/tasks/se-1/inject", WORKER_KEY, "{\"attempt\": 1, \"steps\": \"[]\"}").getResponse().getStatus())
                .isEqualTo(204);
        assertThat(call("/tasks/se-1/inject", WORKER_KEY, "{\"attempt\": 1}").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void aReplyTheEngineCannotTakeIs503WithRetryAfter() throws Exception {
        dispatch("se-1", 1);
        activate(WORKER_KEY, "py-1", 0);
        engineDown = true;

        var result = call("/tasks/se-1/complete", WORKER_KEY, "{\"attempt\": 1}");

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("1");
    }

    @Test
    void theEngineKeyIsAcceptedTooAndAnythingElseIsRefused() throws Exception {
        assertThat(activate(ENGINE_KEY, "w", 0).path("tasks")).isEmpty();
        assertThat(call("/activate", "wrong", "{\"workerId\": \"w\", \"topics\": [\"payments\"]}")
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(call("/activate", null, "{\"workerId\": \"w\", \"topics\": [\"payments\"]}")
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(call("/activate", " ", "{\"workerId\": \"w\", \"topics\": [\"payments\"]}")
                .getResponse().getStatus()).isEqualTo(401);
        for (var path : List.of("/tasks/x/complete", "/tasks/x/fail", "/tasks/x/heartbeat", "/tasks/x/inject")) {
            assertThat(call(path, "wrong", "{}").getResponse().getStatus()).as(path).isEqualTo(401);
        }
    }

    @Test
    void withNoKeysAtAllAnonymousIsRefusedUnlessAllowed() throws Exception {
        apiProperties.setApiKey(null);
        properties.setApiKeys(Map.of());
        assertThat(call("/activate", null, "{\"workerId\": \"w\", \"topics\": [\"payments\"]}")
                .getResponse().getStatus()).isEqualTo(401);

        apiProperties.setAllowAnonymous(true);
        assertThat(activate(null, "w", 0).path("tasks")).isEmpty();
    }
}
