package io.mateu.workflow.infra.in.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import io.mateu.workflow.application.out.UnknownWorkflowDefinitionException;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.sync.Invocation;
import io.mateu.workflow.application.sync.SyncInvocationRejectedException;
import io.mateu.workflow.application.sync.SyncInvocationService;
import io.mateu.workflow.application.sync.SyncReplyWaiters;
import io.mateu.workflow.domain.aggregates.ProcessReply;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.input.InputLimits;
import io.mateu.workflow.security.CallerResolver;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * Synchronous invocation of a durable process: start it, wait up to a deadline, receive the reply
 * its REPLY step emits.
 *
 * <pre>
 *   POST /workflow/api/definitions/{definitionId}/invocations
 *        Idempotency-Key: &lt;key&gt;          (required)
 *        Prefer: wait=10                    (optional, seconds; capped by workflow.sync.max-deadline-ms)
 *        { "businessKey": "...", "variables": { ... } }
 *
 *   GET  /workflow/api/invocations/{invocationId}                     (Prefer: wait=N to long-poll)
 *   GET  /workflow/api/definitions/{definitionId}/invocations?idempotencyKey=...
 * </pre>
 *
 * <p>Answers {@code 200} with the reply, {@code 502} when the process failed (or was cancelled)
 * before replying — the body says which, and whether a compensation is under way — and {@code 202}
 * with a {@code Location} when the deadline passed first: the process carries on and the reply will
 * be at that URL. A retry with the same key never starts a second process.
 *
 * <p>Nothing waits on a servlet thread: the answer is a {@link DeferredResult} completed by
 * {@link SyncReplyWaiters}. Same optional {@code X-Api-Key} as the message API.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/workflow/api")
@RequiredArgsConstructor
@Slf4j
public class SyncInvocationController {

    private static final String MISSING_OR_INVALID_KEY = "Missing or invalid X-Api-Key header";
    private static final Pattern PREFER_WAIT = Pattern.compile("(?i)(?:^|[,;\\s])wait\\s*=\\s*(\\d+)");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    final SyncInvocationService service;
    final SyncReplyWaiters waiters;
    final MessageApiProperties apiProperties;
    final ObjectProvider<CallerResolver> callerResolver;
    final WorkflowMetrics workflowMetrics;

    /** How many callers may be waiting on this pod at once; past it, new ones get a 429. */
    @org.springframework.beans.factory.annotation.Value("${workflow.sync.max-waiting:200}")
    int maxWaiting = 200;

    private Semaphore admission;

    /** Public so an embedder (or a test) that builds the controller by hand can start it. */
    @PostConstruct
    public void init() {
        admission = new Semaphore(Math.max(1, maxWaiting));
        workflowMetrics.syncWaitingGauge(waiters::waitingCount);
    }

    /** The request body. Variables may be any JSON: strings pass through, anything else is kept as JSON text. */
    public record StartBody(String businessKey, Map<String, Object> variables) {
    }

    /** What a caller receives, whatever the status. {@code reply} is the REPLY's JSON, verbatim. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InvocationResponse(String invocationId, String processId, String outcome, String compensation,
                                     @JsonRawValue String reply, String error, String processStatus,
                                     LocalDateTime repliedAt, String location) {
    }

    @PostMapping("/definitions/{definitionId}/invocations")
    public DeferredResult<ResponseEntity<InvocationResponse>> invoke(
            @PathVariable("definitionId") String definitionId,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Prefer", required = false) String prefer,
            @RequestBody(required = false) StartBody body) {
        verifyApiKey(apiKey);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The Idempotency-Key header is required.");
        }
        var variables = variablesOf(body);
        var businessKey = body == null ? null : body.businessKey();
        refuseIfOversized(idempotencyKey, businessKey, variables);

        var receivedAt = System.nanoTime();
        acquireOrRefuse(definitionId);
        try {
            var started = service.start(new SyncInvocationService.StartRequest(definitionId, idempotencyKey,
                    businessKey, variables, waitOf(prefer), caller()));
            return answer(started.invocation(), started.timeToWait(), receivedAt);
        } catch (RuntimeException e) {
            admission.release();
            throw e;
        }
    }

    @GetMapping("/invocations/{invocationId}")
    public DeferredResult<ResponseEntity<InvocationResponse>> get(
            @PathVariable("invocationId") String invocationId,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "Prefer", required = false) String prefer) {
        verifyApiKey(apiKey);
        var invocation = service.find(invocationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invocation."));
        return waitAndAnswer(invocation, prefer);
    }

    @GetMapping("/definitions/{definitionId}/invocations")
    public DeferredResult<ResponseEntity<InvocationResponse>> getByKey(
            @PathVariable("definitionId") String definitionId,
            @RequestParam("idempotencyKey") String idempotencyKey,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "Prefer", required = false) String prefer) {
        verifyApiKey(apiKey);
        var invocation = service.findByKey(definitionId, idempotencyKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No invocation with that key."));
        return waitAndAnswer(invocation, prefer);
    }

    private DeferredResult<ResponseEntity<InvocationResponse>> waitAndAnswer(Invocation invocation, String prefer) {
        var wait = service.waitFor(waitOf(prefer));
        if (wait.isZero()) {
            var result = new DeferredResult<ResponseEntity<InvocationResponse>>();
            result.setResult(toResponse(service.view(invocation)));
            return result;
        }
        acquireOrRefuse(invocation.workflowDefinitionId());
        return answer(invocation, wait, System.nanoTime());
    }

    /** Waits for the reply (the admission permit is already held) and answers whatever comes first. */
    private DeferredResult<ResponseEntity<InvocationResponse>> answer(Invocation invocation, Duration wait, long receivedAt) {
        // The servlet's own timeout is a backstop only: the waiter's timer answers at the deadline.
        var result = new DeferredResult<ResponseEntity<InvocationResponse>>(wait.toMillis() + 30_000);
        result.onTimeout(() -> result.setResult(toResponse(service.view(invocation))));
        result.onCompletion(admission::release);
        service.await(invocation, wait).whenComplete((view, error) -> {
            var response = error == null ? toResponse(view) : toResponse(service.view(invocation));
            workflowMetrics.syncInvocationAnswered(invocation.workflowDefinitionId(),
                    view != null && view.reply() != null ? view.reply().outcome().name() : "DEADLINE",
                    Duration.ofNanos(System.nanoTime() - receivedAt));
            result.setResult(response);
        });
        return result;
    }

    private ResponseEntity<InvocationResponse> toResponse(SyncInvocationService.View view) {
        var invocation = view.invocation();
        var location = "/workflow/api/invocations/" + invocation.id();
        var reply = view.reply();
        var body = new InvocationResponse(invocation.id(), invocation.processId(),
                reply == null ? null : reply.outcome().name(),
                reply == null ? null : reply.compensation().name(),
                reply == null ? null : reply.payload(),
                reply == null ? null : reply.error(),
                view.processStatus() == null ? null : view.processStatus().name(),
                reply == null ? null : reply.repliedAt(),
                location);
        if (reply == null) {
            return ResponseEntity.accepted().location(URI.create(location)).header("Retry-After", "1").body(body);
        }
        return ResponseEntity.status(statusOf(reply)).body(body);
    }

    /** 200 for an answer the process gave; 502 when a step it orchestrated kept it from giving one. */
    static HttpStatus statusOf(ProcessReply reply) {
        return reply.isFailure() ? HttpStatus.BAD_GATEWAY : HttpStatus.OK;
    }

    private void acquireOrRefuse(String definitionId) {
        if (!admission.tryAcquire()) {
            workflowMetrics.syncInvocationRejected(definitionId, SyncInvocationRejectedException.Reason.OVERLOADED.name());
            throw new SyncInvocationRejectedException(SyncInvocationRejectedException.Reason.OVERLOADED,
                    "Too many synchronous invocations are waiting on this node; retry shortly.");
        }
    }

    /** {@code Prefer: wait=N} in seconds (RFC 7240), or null when the caller did not say. */
    static Duration waitOf(String prefer) {
        if (prefer == null) {
            return null;
        }
        var matcher = PREFER_WAIT.matcher(prefer);
        return matcher.find() ? Duration.ofSeconds(Long.parseLong(matcher.group(1))) : null;
    }

    private static Map<String, String> variablesOf(StartBody body) {
        var variables = new LinkedHashMap<String, String>();
        if (body == null || body.variables() == null) {
            return variables;
        }
        body.variables().forEach((name, value) -> variables.put(name, textOf(value)));
        return variables;
    }

    /** Process variables are strings: a string stays itself, anything else is kept as its JSON. */
    private static String textOf(Object value) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private static void refuseIfOversized(String idempotencyKey, String businessKey, Map<String, String> variables) {
        try {
            InputLimits.checkIdentifier(idempotencyKey, "Idempotency-Key");
            if (businessKey != null) {
                InputLimits.checkIdentifier(businessKey, "businessKey");
            }
            InputLimits.checkVariables(variables.entrySet().stream()
                    .map(entry -> new io.mateu.workflow.dtos.Variable(entry.getKey(), entry.getValue()))
                    .toList(), "invocation");
        } catch (InputLimits.InputRejectedException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private io.mateu.workflow.security.AuthorizationContext caller() {
        var resolver = callerResolver.getIfAvailable();
        return resolver == null ? null : resolver.current();
    }

    @ExceptionHandler(SyncInvocationRejectedException.class)
    ResponseEntity<ProblemDetail> onRejected(SyncInvocationRejectedException e) {
        var status = switch (e.reason()) {
            case NOT_SYNC_INVOCABLE, NOT_ACCEPTING, BUSINESS_KEY_TAKEN, LOCK_BUSY -> HttpStatus.CONFLICT;
            case IDEMPOTENCY_KEY_REUSED -> HttpStatus.UNPROCESSABLE_CONTENT;
            case OVERLOADED -> HttpStatus.TOO_MANY_REQUESTS;
        };
        var problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setProperty("reason", e.reason().name());
        var response = ResponseEntity.status(status);
        if (status == HttpStatus.TOO_MANY_REQUESTS || e.reason() == SyncInvocationRejectedException.Reason.LOCK_BUSY) {
            response = response.header("Retry-After", "1");
        }
        return response.body(problem);
    }

    @ExceptionHandler(UnknownWorkflowDefinitionException.class)
    ProblemDetail onUnknownDefinition(UnknownWorkflowDefinitionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(FlowAuthorizationDeniedException.class)
    ProblemDetail onDenied(FlowAuthorizationDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    private void verifyApiKey(String received) {
        String expected = apiProperties.getApiKey();
        if (expected == null || expected.isBlank()) {
            return;
        }
        if (received == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, MISSING_OR_INVALID_KEY);
        }
    }
}
