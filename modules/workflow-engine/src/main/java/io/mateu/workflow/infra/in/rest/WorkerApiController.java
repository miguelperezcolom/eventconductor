package io.mateu.workflow.infra.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.httpworkers.HttpTask;
import io.mateu.workflow.application.httpworkers.HttpTaskException;
import io.mateu.workflow.application.httpworkers.HttpTaskService;
import io.mateu.workflow.application.httpworkers.HttpTaskWaiters;
import io.mateu.workflow.infra.config.HttpWorkersProperties;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Workers over HTTP: a worker in any language long-polls for tasks and answers them here, without
 * Kafka. See the "Workers over HTTP" guide for the protocol.
 *
 * <pre>
 *   POST /workflow/api/workers/activate              {workerId, topics[], maxTasks, leaseMs, waitMs}
 *   POST /workflow/api/workers/tasks/{id}/complete   {attempt, variables{}}
 *   POST /workflow/api/workers/tasks/{id}/fail       {attempt, code, message, retryable}
 *   POST /workflow/api/workers/tasks/{id}/heartbeat  {leaseToken, extendMs}   → {cancelled, deadline}
 *   POST /workflow/api/workers/tasks/{id}/inject     {attempt, steps[]}        (DYNAMIC steps)
 * </pre>
 *
 * <p>Credentials: an {@code X-Api-Key} listed in {@code workflow.workers.http.api-keys} (a worker
 * credential, good for this API only), or whatever lets a request into the rest of the engine API
 * ({@link EngineApiAccess}: the engine api-key, the application's Spring Security, or
 * allow-anonymous). Nothing else gets in.
 *
 * <p>Statuses: 404 the task is gone (answered, cancelled and dropped, or never queued); 409 the
 * reply is for a replaced dispatch ({@code STALE_ATTEMPT}), the lease was re-taken
 * ({@code LEASE_LOST}) or the task was cancelled ({@code CANCELLED}) — a worker drops the task on
 * either; 429 too many activations waiting (retry after a second); 503 the engine did not take the
 * reply (retry it).
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "workflow.workers.http.enabled", havingValue = "true")
@RestController
@RequestMapping("/workflow/api/workers")
@Tag(name = "Workers", description = "Workers over HTTP: long-poll for tasks and answer them, in any language.")
@Slf4j
public class WorkerApiController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ID_LENGTH = 255;
    private static final Duration MIN_LEASE = Duration.ofSeconds(1);

    private final HttpTaskService service;
    private final HttpTaskWaiters waiters;
    private final HttpWorkersProperties properties;
    private final MessageApiProperties apiProperties;

    public WorkerApiController(HttpTaskService service, HttpTaskWaiters waiters, HttpWorkersProperties properties,
                               MessageApiProperties apiProperties) {
        this.service = service;
        this.waiters = waiters;
        this.properties = properties;
        this.apiProperties = apiProperties;
    }

    public record ActivateRequest(String workerId, List<String> topics, Integer maxTasks, Long leaseMs, Long waitMs) {
    }

    public record CompleteRequest(Integer attempt, Map<String, Object> variables) {
    }

    public record FailRequest(Integer attempt, String code, String message, Boolean retryable) {
    }

    public record HeartbeatRequest(String leaseToken, Long extendMs) {
    }

    public record InjectRequest(Integer attempt, Object steps) {
    }

    /**
     * A task handed to a worker. {@code deadline} is when its lease expires (ISO-8601, UTC): heartbeat
     * before it, or the task is offered to another worker.
     */
    public record ActivatedTask(String id, String processId, String workflowDefinitionId, String stepId, String taskId,
                                String topic, Map<String, String> variables, Integer attempt, int delivery,
                                String leaseToken, String deadline) {
    }

    public record Activation(List<ActivatedTask> tasks) {
    }

    public record HeartbeatResponse(boolean cancelled, String deadline) {
    }

    @Operation(operationId = "activateTasks", summary = "Long-poll for tasks",
            description = "Waits up to waitMs (capped by workflow.workers.http.max-wait) for tasks of the given topics and leases them to the caller. An empty list means the wait ran out.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The leased tasks, possibly none."),
            @ApiResponse(responseCode = "400", description = "No workerId or topics.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Too many activations waiting on this node (Retry-After).", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    @PostMapping("/activate")
    public DeferredResult<Activation> activate(@io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                               @RequestBody(required = false) ActivateRequest request) {
        authorize(apiKey);
        if (request == null) {
            throw invalid("A body is required: {workerId, topics, maxTasks, leaseMs, waitMs}.");
        }
        var workerId = request.workerId();
        if (workerId == null || workerId.isBlank() || workerId.length() > MAX_ID_LENGTH) {
            throw invalid("workerId is required (at most " + MAX_ID_LENGTH + " characters).");
        }
        var topics = new LinkedHashSet<String>();
        if (request.topics() != null) {
            request.topics().stream().filter(topic -> topic != null && !topic.isBlank()).map(String::trim)
                    .forEach(topics::add);
        }
        if (topics.isEmpty()) {
            throw invalid("topics is required: the step topics this worker serves.");
        }
        var maxTasks = clamp(request.maxTasks() == null ? 1 : request.maxTasks(), 1, Math.max(1, properties.getMaxTasks()));
        var lease = leaseOf(request.leaseMs(), properties.getDefaultLease());
        var wait = request.waitMs() == null || request.waitMs() <= 0 ? Duration.ZERO
                : min(Duration.ofMillis(request.waitMs()), properties.getMaxWait());

        var future = waiters.activate(workerId, topics, maxTasks, lease, wait);
        var result = new DeferredResult<Activation>(wait.toMillis() + 10_000);
        // Whatever ends the request — a timeout, an error, the caller hanging up — ends the wait, so
        // the slot is freed; tasks the dispatcher hands to a request that is already over are given back.
        result.onTimeout(() -> future.complete(List.of()));
        result.onError(error -> future.complete(List.of()));
        result.onCompletion(() -> future.complete(List.of()));
        future.thenAccept(tasks -> {
            if (!result.setResult(new Activation(tasks.stream().map(WorkerApiController::toActivated).toList()))
                    && !tasks.isEmpty()) {
                service.release(tasks);
            }
        });
        return result;
    }

    @Operation(operationId = "completeTask", summary = "Complete a task",
            description = "Reports the task done with its output variables; echo the attempt it was activated with.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Taken."),
            @ApiResponse(responseCode = "400", description = "Malformed request (for example a missing attempt).", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such task waiting for a reply: answered already, cancelled and dropped, or never served over HTTP.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "STALE_ATTEMPT (the step ran again), or CANCELLED: drop the task.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "The engine could not take the reply; retry it (Retry-After).", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    @PostMapping("/tasks/{id}/complete")
    public ResponseEntity<Void> complete(@io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                         @io.swagger.v3.oas.annotations.Parameter(description = "The task id (the step execution id) from the activation.") @PathVariable("id") String id,
                                         @RequestBody(required = false) CompleteRequest request) {
        authorize(apiKey);
        var body = request == null ? new CompleteRequest(null, null) : request;
        service.complete(id, body.attempt(), body.variables());
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "failTask", summary = "Fail a task",
            description = "Reports a failure: code and message become the reason; retryable=false fails the step without spending its retries.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Taken."),
            @ApiResponse(responseCode = "400", description = "Malformed request (for example a missing attempt).", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such task waiting for a reply: answered already, cancelled and dropped, or never served over HTTP.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "STALE_ATTEMPT (the step ran again), or CANCELLED: drop the task.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "The engine could not take the reply; retry it (Retry-After).", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    @PostMapping("/tasks/{id}/fail")
    public ResponseEntity<Void> fail(@io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                     @io.swagger.v3.oas.annotations.Parameter(description = "The task id (the step execution id) from the activation.") @PathVariable("id") String id,
                                     @RequestBody(required = false) FailRequest request) {
        authorize(apiKey);
        var body = request == null ? new FailRequest(null, null, null, null) : request;
        service.fail(id, body.attempt(), body.code(), body.message(), body.retryable() == null || body.retryable());
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "heartbeatTask", summary = "Extend a task's lease",
            description = "Extends the lease of a running task; cancelled=true means the engine cancelled it: stop and report nothing.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The new deadline, or cancelled."),
            @ApiResponse(responseCode = "400", description = "No leaseToken.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "The task is gone.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "LEASE_LOST: the lease expired and the task was given to another worker.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    @PostMapping("/tasks/{id}/heartbeat")
    public HeartbeatResponse heartbeat(@io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                       @io.swagger.v3.oas.annotations.Parameter(description = "The task id (the step execution id) from the activation.") @PathVariable("id") String id,
                                       @RequestBody(required = false) HeartbeatRequest request) {
        authorize(apiKey);
        var body = request == null ? new HeartbeatRequest(null, null) : request;
        var beat = service.heartbeat(id, body.leaseToken(), leaseOf(body.extendMs(), properties.getDefaultLease()));
        return new HeartbeatResponse(beat.cancelled(), instant(beat.leaseUntil()));
    }

    @Operation(operationId = "injectSteps", summary = "Inject steps (DYNAMIC)",
            description = "Adds steps to the process of a DYNAMIC step's task; complete the task afterwards.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Taken."),
            @ApiResponse(responseCode = "400", description = "Malformed request (for example a missing attempt).", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such task waiting for a reply: answered already, cancelled and dropped, or never served over HTTP.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "STALE_ATTEMPT (the step ran again), or CANCELLED: drop the task.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "The engine could not take the reply; retry it (Retry-After).", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    @PostMapping("/tasks/{id}/inject")
    public ResponseEntity<Void> inject(@io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                       @io.swagger.v3.oas.annotations.Parameter(description = "The task id (the step execution id) from the activation.") @PathVariable("id") String id,
                                       @RequestBody(required = false) InjectRequest request) {
        authorize(apiKey);
        if (request == null || request.steps() == null) {
            throw invalid("steps is required: a JSON array of step objects.");
        }
        String stepsJson;
        try {
            stepsJson = request.steps() instanceof String text ? text : JSON.writeValueAsString(request.steps());
        } catch (Exception e) {
            throw invalid("steps could not be read: " + e.getMessage());
        }
        service.inject(id, request.attempt(), stepsJson);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(HttpTaskException.class)
    ResponseEntity<ProblemDetail> onRefused(HttpTaskException e) {
        var status = switch (e.reason()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case STALE_ATTEMPT, LEASE_LOST, CANCELLED -> HttpStatus.CONFLICT;
            case OVERLOADED -> HttpStatus.TOO_MANY_REQUESTS;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        var problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setProperty("reason", e.reason().name());
        var response = ResponseEntity.status(status);
        if (status == HttpStatus.TOO_MANY_REQUESTS || status == HttpStatus.SERVICE_UNAVAILABLE) {
            response = response.header("Retry-After", "1");
        }
        return response.body(problem);
    }

    /** A worker credential, or whatever the rest of the engine API accepts — never anonymous unless allowed. */
    private void authorize(String apiKey) {
        if (properties.workerCredential(apiKey) != null) {
            return;
        }
        EngineApiAccess.verify(apiProperties, apiKey);
    }

    private Duration leaseOf(Long millis, Duration fallback) {
        var asked = millis == null || millis <= 0 ? fallback : Duration.ofMillis(millis);
        var capped = min(asked, properties.getMaxLease());
        return capped.compareTo(MIN_LEASE) < 0 ? MIN_LEASE : capped;
    }

    private static ActivatedTask toActivated(HttpTask task) {
        var variables = new LinkedHashMap<String, String>();
        task.variables().forEach(variable -> variables.put(variable.name(), variable.value()));
        return new ActivatedTask(task.id(), task.processId(), task.workflowDefinitionId(), task.stepId(), task.taskId(),
                task.topic(), variables, task.attempt(), task.deliveries(), task.leaseToken(),
                instant(task.leaseUntil()));
    }

    /** The service's clock is UTC: a lease expiry as an ISO-8601 instant. */
    private static String instant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC).toString();
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
