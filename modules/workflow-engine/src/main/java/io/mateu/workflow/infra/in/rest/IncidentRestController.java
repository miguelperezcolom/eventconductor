package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.out.IncidentRepository.FailedStep;
import io.mateu.workflow.application.services.IncidentService;
import io.mateu.workflow.application.services.IncidentService.IncidentGroup;
import io.mateu.workflow.application.services.IncidentService.IncidentNotFoundException;
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
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Incidents over HTTP — the failures waiting for an operator, grouped by (definition, step, error
 * family); see {@link IncidentService}.
 *
 * <pre>
 *   GET  /workflow/api/incidents?definitionId=…
 *   GET  /workflow/api/incidents/{key}/processes?limit=…
 *   POST /workflow/api/incidents/{key}/retry        {reason}
 * </pre>
 *
 * <p>Same access rule as the rest of {@code /workflow/api/**} ({@link EngineApiAccess}). Retrying a
 * group is a manual operation, gated like the others: the definition's flow authorization when it is on,
 * and {@code workflow.security.operations.required-roles}.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/workflow/api/incidents")
@Tag(name = "Incidents", description = "Failures waiting for an operator, grouped by definition, step and error family.")
public class IncidentRestController {

    static final int MAX_MEMBERS = 500;

    private final IncidentService incidents;
    private final MessageApiProperties apiProperties;
    private final ObjectProvider<CallerResolver> callerResolver;

    public IncidentRestController(IncidentService incidents, MessageApiProperties apiProperties,
                                  ObjectProvider<CallerResolver> callerResolver) {
        this.incidents = incidents;
        this.apiProperties = apiProperties;
        this.callerResolver = callerResolver;
    }

    @Schema(name = "Incident", description = "One incident: a step of a definition failing with one family of error.")
    public record IncidentView(
            @Schema(description = "Stable id of the group — what /{key}/processes and /{key}/retry take.") String key,
            String definitionId,
            @Schema(description = "The definition's step that failed.") String stepId,
            @Schema(description = "The error message with ids, numbers and quoted values taken out.") String errorFamily,
            @Schema(description = "The most recent error message of the group, as logged.") String sampleError,
            @Schema(description = "Failed step executions in the group.") int failedSteps,
            @Schema(description = "Distinct processes they belong to.") int processes,
            String firstSeen,
            String lastSeen) {

        static IncidentView of(IncidentGroup group) {
            return new IncidentView(group.key(), group.workflowDefinitionId(), group.stepId(), group.errorFamily(),
                    group.sampleError(), group.failedSteps(), group.processes(),
                    ProcessApi.format(group.firstSeen()), ProcessApi.format(group.lastSeen()));
        }
    }

    @Schema(name = "IncidentList", description = "The incidents, most failures first.")
    public record IncidentList(
            List<IncidentView> incidents,
            @Schema(description = "Failed steps read.") int scanned,
            @Schema(description = "Whether there were more than workflow.incidents.scan-limit: the counts are then of"
                    + " the most recent.") boolean truncated) {
    }

    @Schema(name = "IncidentMember", description = "One failed step of an incident.")
    public record IncidentMemberView(String processId, String businessKey, String stepExecutionId,
                                     @Schema(description = "ERROR or TIMEOUT.") String status,
                                     String failedAt, String error) {

        static IncidentMemberView of(FailedStep step) {
            return new IncidentMemberView(step.processId(), step.businessKey(), step.stepExecutionId(),
                    step.status() == null ? null : step.status().name(), ProcessApi.format(step.failedAt()), step.error());
        }
    }

    @Schema(name = "IncidentRetryAccepted", description = "A group retry was accepted (202).")
    public record IncidentRetryAccepted(
            String key,
            @Schema(description = "Failed steps the incident held when the retry was asked for.") int requested,
            @Schema(description = "Always true: the retry is a batch operation, run in the background.") boolean async,
            @Schema(description = "Whether the incident view was truncated (workflow.incidents.scan-limit).") boolean truncated,
            @Schema(description = "The RETRY batch operation over the incident: GET /workflow/api/batch-operations/{id}.")
            String batchOperationId) {
    }

    @GetMapping
    @Operation(operationId = "listIncidents", summary = "List incidents",
            description = "The failed steps of failed (ERROR) processes, grouped by definition, step and error family,"
                    + " most failures first.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The incidents.")})
    public IncidentList list(@Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                             @Parameter(description = "Only this definition's.") @RequestParam(value = "definitionId", required = false) String definitionId) {
        verify(apiKey);
        InputLimits.checkIdentifier(definitionId, "definitionId");
        var found = incidents.incidents(definitionId);
        return new IncidentList(found.groups().stream().map(IncidentView::of).toList(), found.scanned(), found.truncated());
    }

    @GetMapping("/{key}/processes")
    @Operation(operationId = "listIncidentProcesses", summary = "The failed steps of one incident",
            description = "Most recent first — the drill-down to its processes.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Its failed steps."),
            @ApiResponse(responseCode = "404", description = "No such incident (any more).", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public List<IncidentMemberView> members(@Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                            @PathVariable("key") String key,
                                            @Parameter(description = "At most this many, 1–" + MAX_MEMBERS + ".")
                                            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        verify(apiKey);
        if (limit < 1 || limit > MAX_MEMBERS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_MEMBERS + ".");
        }
        var members = incidents.members(key, limit);
        if (members.isEmpty()) {
            throw new IncidentNotFoundException(key);
        }
        return members.stream().map(IncidentMemberView::of).toList();
    }

    @PostMapping("/{key}/retry")
    @Operation(operationId = "retryIncident", summary = "Retry every failed step of one incident",
            description = "As a RETRY batch operation over the incident, in the background: each member's failed"
                    + " steps retried on the node that owns its process, under its lock, recorded in the process's log"
                    + " with the caller and the reason. Location names the batch.")
    @ApiResponses({@ApiResponse(responseCode = "202", description = "Accepted."),
            @ApiResponse(responseCode = "400", description = "No reason.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not operate on the definition.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No such incident (any more).", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<IncidentRetryAccepted> retry(@Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                                       @PathVariable("key") String key,
                                                       @RequestBody(required = false) ProcessApi.ReasonRequest body) {
        verify(apiKey);
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A JSON body is required.");
        }
        var outcome = incidents.retryGroup(key, body.reason(), caller());
        return ResponseEntity.accepted()
                .location(java.net.URI.create("/workflow/api/batch-operations/" + outcome.batchOperationId()))
                .body(new IncidentRetryAccepted(outcome.key(), outcome.requested(), outcome.async(), outcome.truncated(),
                        outcome.batchOperationId()));
    }

    @ExceptionHandler(IncidentNotFoundException.class)
    ProblemDetail onNotFound(IncidentNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
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

    private void verify(String apiKey) {
        EngineApiAccess.verify(apiProperties, apiKey);
    }

    private AuthorizationContext caller() {
        var resolver = callerResolver.getIfAvailable();
        return resolver == null ? null : resolver.current();
    }
}
