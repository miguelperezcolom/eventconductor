package io.mateu.workflow.infra.in.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import io.mateu.workflow.application.updates.ProcessUpdate;
import io.mateu.workflow.application.updates.ProcessUpdateRejectedException;
import io.mateu.workflow.application.updates.ProcessUpdateService;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.input.InputLimits;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.CallerResolver;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Updates of running processes over HTTP: a validated write to a live process that answers with a result
 * (see {@code updates} in the definition, {@link ProcessUpdateService}).
 *
 * <pre>
 *   POST /workflow/api/processes/{id}/updates/{name}                  body: the payload (JSON)
 *   POST /workflow/api/processes/by-key/updates/{name}?businessKey=…
 *   POST /workflow/api/definitions/{definitionId}/update-with-start   {businessKey, variables?, update, payload?}
 *   GET  /workflow/api/updates/{updateId}                              (Prefer: wait=N long-polls)
 *   GET  /workflow/api/processes/{id}/updates
 * </pre>
 *
 * <p>{@code Idempotency-Key} (optional, recommended) makes a retry land on the same update and get its
 * recorded outcome; {@code Prefer: wait=N} bounds the wait (capped by {@code workflow.sync.max-deadline-ms}).
 * {@code 200} with the result once applied; {@code 202} + {@code Location} when the deadline passed first —
 * the update still completes, durably, and is read there. Refusals are {@code application/problem+json}
 * with a {@code reason}: 400 invalid payload, 404 no such process/update, 409 not accepting, 422 refused by
 * a guard (or a reused key), 500 an expression failed — in every case nothing changed.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/workflow/api")
@Tag(name = "Updates", description = "Validated writes to running processes that answer with a result.")
@Slf4j
public class ProcessUpdateController {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final String PROBLEM = "application/problem+json";

    private final ProcessUpdateService service;
    private final MessageApiProperties apiProperties;
    private final ObjectProvider<CallerResolver> callerResolver;

    /** How many callers may be waiting on this node at once; past it, new ones get a 429. */
    @org.springframework.beans.factory.annotation.Value("${workflow.sync.max-waiting:200}")
    int maxWaiting = 200;

    private Semaphore admission;

    public ProcessUpdateController(ProcessUpdateService service, MessageApiProperties apiProperties,
                                   ObjectProvider<CallerResolver> callerResolver) {
        this.service = service;
        this.apiProperties = apiProperties;
        this.callerResolver = callerResolver;
    }

    /** Public so an embedder (or a test) that builds the controller by hand can start it. */
    @PostConstruct
    public void init() {
        admission = new Semaphore(Math.max(1, maxWaiting));
    }

    /** What a caller receives. {@code result} is the update's reply, verbatim JSON. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "ProcessUpdateResponse")
    public record UpdateResponse(
            @Schema(description = "The update's id; GET /workflow/api/updates/{updateId} reads it again.") String updateId,
            String processId,
            @Schema(description = "The update's name in the definition.") String name,
            @Schema(description = "REQUESTED (not applied yet: 202), COMPLETED, REFUSED, NOT_ACCEPTED or FAILED.") String status,
            @Schema(description = "The update's reply (any JSON) when COMPLETED.", type = "object") @JsonRawValue String result,
            @Schema(description = "Why it was refused, not accepted or failed.") String error,
            @Schema(description = "The payload as recorded: fields the update declares sensitive are masked.", type = "object") @JsonRawValue String payload,
            String requestedBy,
            @Schema(description = "Whether this update started its process (update-with-start).") Boolean started,
            LocalDateTime requestedAt,
            LocalDateTime completedAt,
            @Schema(description = "Where the update is read again.") String location) {

        static UpdateResponse of(ProcessUpdate update) {
            return new UpdateResponse(update.id(), update.processId(), update.name(), update.status().name(),
                    update.result(), update.error(), update.payload(), update.requestedBy(),
                    update.withStart() ? Boolean.TRUE : null, update.requestedAt(), update.completedAt(),
                    "/workflow/api/updates/" + update.id());
        }
    }

    /** The body of an update-with-start. */
    public record UpdateWithStartRequest(
            @Schema(description = "The process's business key: the existing process with it gets the update, or a new one is started with it.", requiredMode = Schema.RequiredMode.REQUIRED) String businessKey,
            @Schema(description = "Initial variables of a process started by this call (ignored when it exists). Values that are not text are stored as JSON.") Map<String, Object> variables,
            @Schema(description = "The update's name.", requiredMode = Schema.RequiredMode.REQUIRED) String update,
            @Schema(description = "The update's payload (any JSON).", type = "object") Object payload) {
    }

    // ── Update ─────────────────────────────────────────────────────────────────────────────────────

    @PostMapping(value = "/processes/{id}/updates/{name}")
    @Operation(operationId = "updateProcess", summary = "Apply an update to a running process",
            description = "Validates the payload against the update's `accepts` schema, then — on the node that owns"
                    + " the process, under its lock — checks it is accepting, runs the `validate` guards, writes `set`"
                    + " and answers `reply`. Any number of updates over a process's life, one at a time per process.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Applied; `result` is the reply."),
            @ApiResponse(responseCode = "202", description = "The deadline passed first; the update completes durably — read it at Location."),
            @ApiResponse(responseCode = "400", description = "The payload is not JSON or not what the update accepts.", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not update the process.", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such process, or its definition declares no such update.", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The process is not accepting the update now.", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "A guard refused it, or the Idempotency-Key was used for another request.", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Too many callers waiting on this node.", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "An expression of the update failed; nothing changed.", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))})
    public DeferredResult<ResponseEntity<Object>> update(
            @Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @Parameter(description = "Makes a retry land on the same update and get its recorded outcome.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Parameter(description = "RFC 7240 `wait=N`: seconds to wait for the outcome before answering 202.")
            @RequestHeader(value = "Prefer", required = false) String prefer,
            @PathVariable("id") String id,
            @PathVariable("name") String name,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "The update's payload (any JSON).",
                    content = @Content(mediaType = "application/json", schema = @Schema(type = "object")))
            @RequestBody(required = false) String payload) {
        verify(apiKey);
        return submit(new ProcessUpdateService.UpdateRequest(id, null, name, blankToNull(payload), blankToNull(idempotencyKey),
                SyncInvocationController.waitOf(prefer), caller()));
    }

    @PostMapping(value = "/processes/by-key/updates/{name}")
    @Operation(operationId = "updateProcessByBusinessKey", summary = "Apply an update to the running process with a business key",
            description = "As `updateProcess`, finding the process by its business key.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Applied; `result` is the reply."),
            @ApiResponse(responseCode = "202", description = "The deadline passed first; read the update at Location."),
            @ApiResponse(responseCode = "404", description = "No process has the key, or no such update.", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The process is not accepting the update now.", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "A guard refused it.", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))})
    public DeferredResult<ResponseEntity<Object>> updateByBusinessKey(
            @Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Prefer", required = false) String prefer,
            @RequestParam("businessKey") String businessKey,
            @PathVariable("name") String name,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "The update's payload (any JSON).",
                    content = @Content(mediaType = "application/json", schema = @Schema(type = "object")))
            @RequestBody(required = false) String payload) {
        verify(apiKey);
        InputLimits.checkIdentifier(businessKey, "businessKey");
        return submit(new ProcessUpdateService.UpdateRequest(null, businessKey, name, blankToNull(payload),
                blankToNull(idempotencyKey), SyncInvocationController.waitOf(prefer), caller()));
    }

    @PostMapping(value = "/definitions/{definitionId}/update-with-start")
    @Operation(operationId = "updateWithStart", summary = "Update the process with a business key, starting it if there is none",
            description = "With no process for the key, starts one whose first act is the update: evaluated against"
                    + " the initial variables, its `set` seeds them, and the process and its completed update are"
                    + " created together. With one, delivers the update to it (as `updateProcess`). A refused update"
                    + " starts nothing. `started` says which happened.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Applied; `started` says whether this call started the process."),
            @ApiResponse(responseCode = "202", description = "The deadline passed first; read the update at Location."),
            @ApiResponse(responseCode = "400", description = "No business key, or an invalid payload.", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such definition or update.", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The key belongs to another definition's process, or the process is not accepting.", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "A guard refused it.", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))})
    public DeferredResult<ResponseEntity<Object>> updateWithStart(
            @Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Prefer", required = false) String prefer,
            @PathVariable("definitionId") String definitionId,
            @RequestBody(required = false) UpdateWithStartRequest body) {
        verify(apiKey);
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A JSON body is required.");
        }
        var variables = new LinkedHashMap<String, String>();
        if (body.variables() != null) {
            body.variables().forEach((name, value) -> variables.put(name, textOf(value)));
        }
        var payload = body.payload() == null ? null : json(body.payload());
        acquireOrRefuse();
        try {
            var started = service.updateWithStart(new ProcessUpdateService.StartRequest(definitionId, body.businessKey(),
                    variables, body.update(), payload, blankToNull(idempotencyKey), SyncInvocationController.waitOf(prefer),
                    caller()));
            return answer(started.update(), started.timeToWait(), true);
        } catch (RuntimeException e) {
            admission.release();
            throw e;
        }
    }

    // ── Reads ──────────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/updates/{updateId}")
    @Operation(operationId = "getProcessUpdate", summary = "Read an update",
            description = "Answered as its POST was: 200 with the result, 202 while not applied yet (Prefer: wait=N"
                    + " long-polls), the problem of a refusal otherwise.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Applied."),
            @ApiResponse(responseCode = "202", description = "Not applied yet."),
            @ApiResponse(responseCode = "404", description = "No such update.", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))})
    public DeferredResult<ResponseEntity<Object>> get(
            @Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "Prefer", required = false) String prefer,
            @PathVariable("updateId") String updateId) {
        verify(apiKey);
        var update = service.find(updateId, caller())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such update."));
        var requested = SyncInvocationController.waitOf(prefer);
        var wait = service.waitFor(requested == null ? Duration.ZERO : requested);
        if (wait.isZero() || update.isFinal()) {
            var result = new DeferredResult<ResponseEntity<Object>>();
            result.setResult(toResponse(shownOrSelf(update)));
            return result;
        }
        acquireOrRefuse();
        return answer(update, wait, false);
    }

    @GetMapping("/processes/{id}/updates")
    @Operation(operationId = "getProcessUpdates", summary = "The updates of a process", description = "Oldest first.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Its updates."),
            @ApiResponse(responseCode = "404", description = "No such process.", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))})
    public List<UpdateResponse> list(
            @Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @PathVariable("id") String id) {
        verify(apiKey);
        return service.list(id, caller()).stream().map(this::shownOrSelf).map(UpdateResponse::of).toList();
    }

    // ── Answering ──────────────────────────────────────────────────────────────────────────────────

    private DeferredResult<ResponseEntity<Object>> submit(ProcessUpdateService.UpdateRequest request) {
        acquireOrRefuse();
        try {
            var submitted = service.submit(request);
            return answer(submitted.update(), submitted.timeToWait(), true);
        } catch (RuntimeException e) {
            admission.release();
            throw e;
        }
    }

    /**
     * Waits for the outcome (the admission permit is held) and answers whatever comes first — to the caller
     * that sent the update ({@code forCaller}: a sealed result opened for it), or to a reader (masked).
     */
    private DeferredResult<ResponseEntity<Object>> answer(ProcessUpdate update, Duration wait, boolean forCaller) {
        var result = new DeferredResult<ResponseEntity<Object>>(wait.toMillis() + 30_000);
        result.onTimeout(() -> result.setResult(toResponse(view(update, forCaller))));
        result.onCompletion(admission::release);
        service.await(update, wait).whenComplete((latest, error) ->
                result.setResult(toResponse(view(error == null && latest != null ? latest : update, forCaller))));
        return result;
    }

    private ProcessUpdate view(ProcessUpdate update, boolean forCaller) {
        var shown = forCaller ? service.forCaller(update) : service.shownForProcess(update);
        return shown == null ? ProcessUpdateService.shown(update) : shown;
    }

    private ProcessUpdate shownOrSelf(ProcessUpdate update) {
        return view(update, false);
    }

    static ResponseEntity<Object> toResponse(ProcessUpdate update) {
        var body = UpdateResponse.of(update);
        return switch (update.status()) {
            case REQUESTED -> ResponseEntity.accepted().location(URI.create(body.location()))
                    .header("Retry-After", "1").body(body);
            case COMPLETED -> ResponseEntity.ok().location(URI.create(body.location())).body(body);
            case REFUSED -> problem(HttpStatus.UNPROCESSABLE_CONTENT, "REFUSED", update);
            case NOT_ACCEPTED -> problem(HttpStatus.CONFLICT, "NOT_ACCEPTING", update);
            case FAILED -> problem(HttpStatus.INTERNAL_SERVER_ERROR, "FAILED", update);
        };
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String reason, ProcessUpdate update) {
        var problem = ProblemDetail.forStatusAndDetail(status, update.error());
        problem.setProperty("reason", reason);
        problem.setProperty("updateId", update.id());
        problem.setProperty("processId", update.processId());
        problem.setProperty("status", update.status().name());
        return ResponseEntity.status(status).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .location(URI.create("/workflow/api/updates/" + update.id())).body(problem);
    }

    static HttpStatus statusOf(ProcessUpdateRejectedException.Reason reason) {
        return switch (reason) {
            case NO_SUCH_PROCESS, NO_SUCH_UPDATE -> HttpStatus.NOT_FOUND;
            case INVALID_PAYLOAD -> HttpStatus.BAD_REQUEST;
            case NOT_ACCEPTING, BUSINESS_KEY_TAKEN -> HttpStatus.CONFLICT;
            case REFUSED, IDEMPOTENCY_KEY_REUSED -> HttpStatus.UNPROCESSABLE_CONTENT;
            case FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
            case OVERLOADED -> HttpStatus.TOO_MANY_REQUESTS;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    private void acquireOrRefuse() {
        if (!admission.tryAcquire()) {
            throw new ProcessUpdateRejectedException(ProcessUpdateRejectedException.Reason.OVERLOADED,
                    "Too many callers are waiting for updates on this node; retry shortly.");
        }
    }

    // ── Errors: application/problem+json ─────────────────────────────────────────────────────────

    @ExceptionHandler(ProcessUpdateRejectedException.class)
    ResponseEntity<ProblemDetail> onRejected(ProcessUpdateRejectedException e) {
        var status = statusOf(e.reason());
        var problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setProperty("reason", e.reason().name());
        if (e.update() != null) {
            problem.setProperty("updateId", e.update().id());
        }
        var response = ResponseEntity.status(status);
        if (status == HttpStatus.TOO_MANY_REQUESTS || status == HttpStatus.SERVICE_UNAVAILABLE) {
            response = response.header("Retry-After", "1");
        }
        return response.body(problem);
    }

    @ExceptionHandler(FlowAuthorizationDeniedException.class)
    ProblemDetail onDenied(FlowAuthorizationDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler({InputLimits.InputRejectedException.class, IllegalArgumentException.class})
    ProblemDetail onInvalid(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> onStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(ProblemDetail.forStatusAndDetail(e.getStatusCode(), e.getReason()));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────────────────

    private void verify(String apiKey) {
        EngineApiAccess.verify(apiProperties, apiKey);
    }

    private AuthorizationContext caller() {
        var resolver = callerResolver.getIfAvailable();
        return resolver == null ? null : resolver.current();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("The payload is not representable as JSON: " + e.getOriginalMessage());
        }
    }

    /** Process variables are strings: a string stays itself, anything else is kept as its JSON. */
    private static String textOf(Object value) {
        return value == null || value instanceof String ? (String) value : json(value);
    }
}
