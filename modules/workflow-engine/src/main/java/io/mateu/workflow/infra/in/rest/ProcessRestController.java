package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.out.ProcessListingFilter;
import io.mateu.workflow.application.out.UnknownWorkflowDefinitionException;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.application.services.ProcessOperationsService.BusinessKeyTakenException;
import io.mateu.workflow.application.services.ProcessOperationsService.NotAcceptingInstancesException;
import io.mateu.workflow.application.services.ProcessOperationsService.ProcessNotFoundException;
import io.mateu.workflow.application.services.ProcessOperationsService.ProcessStateConflictException;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.dtos.TraceContext;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.infra.in.rest.ProcessApi.CommandAccepted;
import io.mateu.workflow.infra.in.rest.ProcessApi.LogEntryView;
import io.mateu.workflow.infra.in.rest.ProcessApi.ProcessPage;
import io.mateu.workflow.infra.in.rest.ProcessApi.ProcessView;
import io.mateu.workflow.infra.in.rest.ProcessApi.StartProcessRequest;
import io.mateu.workflow.infra.in.rest.ProcessApi.StepView;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Processes over HTTP — start, read, list, and the operator commands — on the same application
 * services the admin console and the MCP tools use (see {@link ProcessOperationsService}).
 *
 * <pre>
 *   POST /workflow/api/processes                                  start {definitionId, businessKey?, variables?}
 *   GET  /workflow/api/processes?definitionId=&amp;tag=&amp;status=&amp;from=&amp;to=&amp;businessKey=&amp;q=&amp;page=&amp;size=
 *   GET  /workflow/api/processes/by-key?businessKey=…
 *   GET  /workflow/api/processes/{id}
 *   GET  /workflow/api/processes/{id}/steps
 *   GET  /workflow/api/processes/{id}/variables
 *   GET  /workflow/api/processes/{id}/logs
 *   POST /workflow/api/processes/{id}/cancel | /pause | /resume | /retry | /restart
 *   POST /workflow/api/processes/{id}/steps/{stepExecutionId}/retry
 * </pre>
 *
 * <p>A start answers {@code 201} with a {@code Location}; the same business key and definition again
 * answers {@code 200} with the process already there — a retried request never starts a second one —
 * and a key taken by another definition {@code 409}. A command answers {@code 202}: it is carried out
 * by the node that owns the process, so read the process for the outcome. A command the process's
 * status does not allow (retry a RUNNING process, cancel a COMPLETED one) is {@code 409}.
 *
 * <p>Same access rule as the rest of {@code /workflow/api/**} ({@link EngineApiAccess}): the
 * {@code X-Api-Key}, or the application's Spring Security, or an explicit allow-anonymous. With
 * {@code workflow.security.flow-authorization.enabled}, a definition's {@code requiredScopes}/
 * {@code requiredRoles} also gate starting it and reading or commanding its processes ({@code 403}).
 * Errors are {@code application/problem+json}.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/workflow/api/processes")
@Tag(name = "Processes", description = "Start, read, list and operate processes.")
public class ProcessRestController {

    /** The largest page the listing serves. */
    static final int MAX_PAGE_SIZE = 500;

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private final ProcessOperationsService processes;
    private final MessageApiProperties apiProperties;
    private final ObjectProvider<CallerResolver> callerResolver;

    public ProcessRestController(ProcessOperationsService processes, MessageApiProperties apiProperties,
                                 ObjectProvider<CallerResolver> callerResolver) {
        this.processes = processes;
        this.apiProperties = apiProperties;
        this.callerResolver = callerResolver;
    }

    // ── Start ────────────────────────────────────────────────────────────────────────────────────

    @PostMapping
    @Operation(operationId = "startProcess", summary = "Start a process",
            description = "Starts a process of the definition. Idempotent on the business key: the same key and"
                    + " definition again returns the existing process with 200.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Started; Location names the new process."),
            @ApiResponse(responseCode = "200", description = "A process of this definition already has the business key."),
            @ApiResponse(responseCode = "400", description = "No definitionId, or over the engine's input limits.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not start the definition.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such definition.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The key is taken by another definition's process, or the definition accepts no new processes.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<ProcessView> start(
            @Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @Parameter(description = "W3C trace context: the process joins the caller's trace.")
            @RequestHeader(value = TraceContext.TRACEPARENT, required = false) String traceparent,
            @Parameter(hidden = true) @RequestHeader(value = TraceContext.TRACESTATE, required = false) String tracestate,
            @Parameter(hidden = true) @RequestHeader(value = TraceContext.BAGGAGE, required = false) String baggage,
            @RequestBody(required = false) StartProcessRequest body) {
        verify(apiKey);
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A JSON body is required.");
        }
        var outcome = processes.start(body.definitionId(), body.businessKey(), variablesOf(body.variables()),
                caller(), TraceContext.of(traceparent, tracestate, baggage));
        var view = ProcessView.of(outcome.process());
        var location = URI.create("/workflow/api/processes/" + view.id());
        return outcome.created()
                ? ResponseEntity.created(location).body(view)
                : ResponseEntity.ok().location(location).body(view);
    }

    // ── Reads ────────────────────────────────────────────────────────────────────────────────────

    @GetMapping
    @Operation(operationId = "listProcesses", summary = "List processes",
            description = "One page of processes, newest first, narrowed by every filter given (ANDed). Served from"
                    + " the process index (workflow.projection.enabled) when only q is given, from the engine's"
                    + " store otherwise — the same rule as the admin console's listing.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "One page of processes."),
            @ApiResponse(responseCode = "400", description = "A malformed filter or page.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public ProcessPage list(
            @Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @Parameter(description = "Only processes of this definition.") @RequestParam(value = "definitionId", required = false) String definitionId,
            @Parameter(description = "Only processes of the definitions carrying this tag (case-insensitive).") @RequestParam(value = "tag", required = false) String tag,
            @Parameter(description = "Only processes in any of these statuses; repeat it or separate with commas.") @RequestParam(value = "status", required = false) List<String> status,
            @Parameter(description = "Created at or after: an ISO date-time, or a date (its first moment).") @RequestParam(value = "from", required = false) String from,
            @Parameter(description = "Created at or before: an ISO date-time, or a date (its last moment).") @RequestParam(value = "to", required = false) String to,
            @Parameter(description = "Business key contains this (case-insensitive).") @RequestParam(value = "businessKey", required = false) String businessKey,
            @Parameter(description = "Name or business key contains this (case-insensitive).") @RequestParam(value = "q", required = false) String q,
            @Parameter(description = "Zero-based page.") @RequestParam(value = "page", defaultValue = "0") int page,
            @Parameter(description = "Page size, 1–" + MAX_PAGE_SIZE + ".") @RequestParam(value = "size", defaultValue = "20") int size) {
        verify(apiKey);
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be 0 or more.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        InputLimits.checkIdentifier(definitionId, "definitionId");
        InputLimits.checkIdentifier(tag, "tag");
        InputLimits.checkIdentifier(businessKey, "businessKey");
        InputLimits.checkIdentifier(q, "q");
        var filter = new ProcessListingFilter(blankToNull(q), false, blankToNull(definitionId), statuses(status),
                moment(from, "from", false), moment(to, "to", true), blankToNull(businessKey),
                processes.definitionsTagged(blankToNull(tag)));
        return ProcessPage.of(processes.list(filter, page, size));
    }

    @GetMapping("/by-key")
    @Operation(operationId = "getProcessByBusinessKey", summary = "Find a process by its business key", description = "Exact match.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The process."),
            @ApiResponse(responseCode = "404", description = "No process has the key.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public ProcessView byBusinessKey(
            @Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestParam("businessKey") String businessKey) {
        verify(apiKey);
        InputLimits.checkIdentifier(businessKey, "businessKey");
        return ProcessView.of(processes.getByBusinessKey(businessKey, caller()));
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getProcess", summary = "Get a process")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The process."),
            @ApiResponse(responseCode = "404", description = "No such process.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public ProcessView get(@Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                           @PathVariable("id") String id) {
        verify(apiKey);
        return ProcessView.of(processes.get(id, caller()));
    }

    @GetMapping("/{id}/steps")
    @Operation(operationId = "getProcessSteps", summary = "The process's step executions", description = "In the order they were created.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Its step executions."),
            @ApiResponse(responseCode = "404", description = "No such process.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public List<StepView> steps(@Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                @PathVariable("id") String id) {
        verify(apiKey);
        return processes.steps(id, caller()).stream().map(StepView::of).toList();
    }

    @GetMapping("/{id}/variables")
    @Operation(operationId = "getProcessVariables", summary = "The process's variables", description = "By name; values are strings.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Its variables, by name."),
            @ApiResponse(responseCode = "404", description = "No such process.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public Map<String, String> variables(@Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                         @PathVariable("id") String id) {
        verify(apiKey);
        return ProcessApi.variablesOf(processes.get(id, caller()));
    }

    @GetMapping("/{id}/logs")
    @Operation(operationId = "getProcessLogs", summary = "The process's log", description = "Oldest first.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Its log, oldest first."),
            @ApiResponse(responseCode = "404", description = "No such process.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public List<LogEntryView> logs(@Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                   @PathVariable("id") String id) {
        verify(apiKey);
        return processes.logs(id, caller()).stream().map(LogEntryView::of).toList();
    }

    // ── Commands ─────────────────────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/cancel")
    @Operation(operationId = "cancelProcess", summary = "Cancel a process", description = "Its live steps are cancelled and the workers told. Not for a COMPLETED process.")
    @ApiResponses({@ApiResponse(responseCode = "202", description = "Accepted."),
            @ApiResponse(responseCode = "404", description = "No such process.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The process's status does not allow it.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<CommandAccepted> cancel(@Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                                  @PathVariable("id") String id) {
        verify(apiKey);
        processes.cancel(id, caller());
        return accepted(id, "cancel", null);
    }

    @PostMapping("/{id}/pause")
    @Operation(operationId = "pauseProcess", summary = "Pause a process", description = "PENDING or RUNNING: no new steps start and timer/timeout clocks freeze.")
    @ApiResponses({@ApiResponse(responseCode = "202", description = "Accepted."),
            @ApiResponse(responseCode = "404", description = "No such process.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The process's status does not allow it.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<CommandAccepted> pause(@Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                                 @PathVariable("id") String id) {
        verify(apiKey);
        processes.pause(id, caller());
        return accepted(id, "pause", null);
    }

    @PostMapping("/{id}/resume")
    @Operation(operationId = "resumeProcess", summary = "Resume a paused process", description = "PAUSED only: clocks shift by the pause and the flow moves on.")
    @ApiResponses({@ApiResponse(responseCode = "202", description = "Accepted."),
            @ApiResponse(responseCode = "404", description = "No such process.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The process's status does not allow it.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<CommandAccepted> resume(@Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                                  @PathVariable("id") String id) {
        verify(apiKey);
        processes.resume(id, caller());
        return accepted(id, "resume", null);
    }

    @PostMapping("/{id}/retry")
    @Operation(operationId = "retryProcess", summary = "Retry a stopped process from where it stopped",
            description = "ERROR or CANCELLED: the failed (or cancelled) steps run again; the ones that succeeded are left alone.")
    @ApiResponses({@ApiResponse(responseCode = "202", description = "Accepted."),
            @ApiResponse(responseCode = "404", description = "No such process.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The process's status does not allow it.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<CommandAccepted> retry(@Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                                 @PathVariable("id") String id) {
        verify(apiKey);
        processes.retry(id, caller());
        return accepted(id, "retry", null);
    }

    @PostMapping("/{id}/restart")
    @Operation(operationId = "restartProcess", summary = "Restart a stopped process from the beginning",
            description = "ERROR or CANCELLED: every step runs again, including the ones that succeeded, with the variables it was created with.")
    @ApiResponses({@ApiResponse(responseCode = "202", description = "Accepted."),
            @ApiResponse(responseCode = "404", description = "No such process.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The process's status does not allow it.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<CommandAccepted> restart(@Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                                   @PathVariable("id") String id) {
        verify(apiKey);
        processes.restart(id, caller());
        return accepted(id, "restart", null);
    }

    @PostMapping("/{id}/steps/{stepExecutionId}/retry")
    @Operation(operationId = "retryStepExecution", summary = "Retry one failed step execution",
            description = "An ERROR or TIMEOUT step of a process that is not finished (CANCELLED, COMPLETED, COMPENSATED, COMPENSATION_FAILED).")
    @ApiResponses({@ApiResponse(responseCode = "202", description = "Accepted."),
            @ApiResponse(responseCode = "404", description = "No such process, or no such step execution of it.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The step did not fail, or the process is finished.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<CommandAccepted> retryStep(@Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                                     @PathVariable("id") String id,
                                                     @PathVariable("stepExecutionId") String stepExecutionId) {
        verify(apiKey);
        processes.retryStep(id, stepExecutionId, caller());
        return accepted(id, "retry-step", stepExecutionId);
    }

    // ── Errors: application/problem+json ─────────────────────────────────────────────────────────

    @ExceptionHandler(ProcessNotFoundException.class)
    ProblemDetail onNotFound(ProcessNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(UnknownWorkflowDefinitionException.class)
    ProblemDetail onUnknownDefinition(UnknownWorkflowDefinitionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ProcessStateConflictException.class)
    ProblemDetail onStateConflict(ProcessStateConflictException e) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        if (e.status() != null) {
            problem.setProperty("processStatus", e.status().name());
        }
        return problem;
    }

    @ExceptionHandler({BusinessKeyTakenException.class, NotAcceptingInstancesException.class})
    ProblemDetail onConflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(FlowAuthorizationDeniedException.class)
    ProblemDetail onDenied(FlowAuthorizationDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler({InputLimits.InputRejectedException.class, IllegalArgumentException.class})
    ProblemDetail onInvalid(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail onBadParameter(MethodArgumentTypeMismatchException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "'" + e.getName() + "' is not valid: " + e.getValue());
    }

    /** The 401 of {@link EngineApiAccess} and this controller's own 400s, as problem+json like the rest. */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> onStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(ProblemDetail.forStatusAndDetail(e.getStatusCode(), e.getReason()));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

    private static ResponseEntity<CommandAccepted> accepted(String processId, String command, String stepExecutionId) {
        return ResponseEntity.accepted()
                .location(URI.create("/workflow/api/processes/" + processId))
                .body(new CommandAccepted(processId, command, stepExecutionId));
    }

    private void verify(String apiKey) {
        EngineApiAccess.verify(apiProperties, apiKey);
    }

    /** The caller as resolved at this request, for the flow-authorization checks. */
    private AuthorizationContext caller() {
        var resolver = callerResolver.getIfAvailable();
        return resolver == null ? null : resolver.current();
    }

    /** Process variables are strings: a string stays itself, anything else is kept as its JSON. */
    static List<Variable> variablesOf(Map<String, Object> variables) {
        if (variables == null) {
            return List.of();
        }
        var list = new ArrayList<Variable>(variables.size());
        variables.forEach((name, value) -> list.add(new Variable(name, textOf(value))));
        return list;
    }

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

    /** Repeated or comma-separated; an unknown status is a 400, not "no filter". */
    static Set<ProcessStatus> statuses(List<String> raw) {
        if (raw == null) {
            return null;
        }
        var statuses = EnumSet.noneOf(ProcessStatus.class);
        for (var value : raw) {
            for (var token : value.split(",")) {
                var trimmed = token.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    statuses.add(ProcessStatus.valueOf(trimmed.toUpperCase(java.util.Locale.ROOT)));
                } catch (IllegalArgumentException unknown) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status '" + trimmed
                            + "'; one of " + java.util.Arrays.toString(ProcessStatus.values()) + ".");
                }
            }
        }
        return statuses.isEmpty() ? null : statuses;
    }

    /** An ISO date-time, or a date: its first moment for a lower bound, its last for an upper one. */
    static LocalDateTime moment(String raw, String name, boolean upperBound) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim());
        } catch (DateTimeParseException notADateTime) {
            try {
                var day = LocalDate.parse(raw.trim());
                return upperBound ? day.atTime(LocalTime.MAX) : day.atStartOfDay();
            } catch (DateTimeParseException notADate) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'" + name
                        + "' must be an ISO date-time (2026-10-18T09:30:00) or date (2026-10-18): " + raw);
            }
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
