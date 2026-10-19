package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.services.BatchOperationService;
import io.mateu.workflow.application.services.BatchOperationService.BatchOperationNotActiveException;
import io.mateu.workflow.application.services.BatchOperationService.BatchOperationNotFoundException;
import io.mateu.workflow.domain.batch.BatchOperationFilter;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.infra.in.rest.BatchOperationApi.BatchOperationView;
import io.mateu.workflow.infra.in.rest.BatchOperationApi.CreateBatchOperationRequest;
import io.mateu.workflow.input.InputLimits;
import io.mateu.workflow.search.SearchAttributeFilter;
import io.mateu.workflow.search.SearchQuery;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.CallerResolver;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Batch operations over HTTP — an operator command applied to every process a filter matches.
 *
 * <pre>
 *   POST /workflow/api/batch-operations               {type, filter: {definitionId?, tag?, status?, from?, to?,
 *                                                      businessKey?, q?, onlyErrors?, query?, attributes?},
 *                                                      variables?, remove?, stepId?, reason?,
 *                                                      targetVersion?, fromVersion?, stepMapping?}
 *   GET  /workflow/api/batch-operations               the most recent first
 *   GET  /workflow/api/batch-operations/{id}          one, with its progress and errors
 *   POST /workflow/api/batch-operations/{id}/cancel   stop it: no further process is touched
 * </pre>
 *
 * <p>Creating answers {@code 202} with the batch — {@code CREATED}, then walked by the runner in the
 * background; read it for the progress. The filter is the process listing's, and must narrow something.
 * Each process is then commanded as a single {@code POST /workflow/api/processes/{id}/<command>} would be,
 * judged against <em>this request's</em> caller: with flow authorization on, a process the caller may not
 * operate is skipped ({@code notAuthorized}), not failed.
 *
 * <p>Same access rule as the rest of {@code /workflow/api/**} ({@link EngineApiAccess}). Errors are
 * {@code application/problem+json}.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/workflow/api/batch-operations")
@Tag(name = "Batch operations", description = "Cancel, retry, restart, pause or resume every process a filter matches.")
public class BatchOperationRestController {

    private final BatchOperationService batches;
    private final MessageApiProperties apiProperties;
    private final ObjectProvider<CallerResolver> callerResolver;

    public BatchOperationRestController(BatchOperationService batches, MessageApiProperties apiProperties,
                                        ObjectProvider<CallerResolver> callerResolver) {
        this.batches = batches;
        this.apiProperties = apiProperties;
        this.callerResolver = callerResolver;
    }

    @PostMapping
    @Operation(operationId = "createBatchOperation", summary = "Create a batch operation",
            description = "Applies the command to every process the filter matches, in the background, a page at a time.")
    @ApiResponses({@ApiResponse(responseCode = "202", description = "Accepted; Location names the batch."),
            @ApiResponse(responseCode = "400", description = "No type, an unknown one, an empty filter or a malformed one.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public ResponseEntity<BatchOperationView> create(
            @Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestBody(required = false) CreateBatchOperationRequest body) {
        verify(apiKey);
        if (body == null || body.filter() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A JSON body with a type and a filter is required.");
        }
        var set = new java.util.LinkedHashMap<String, String>();
        if (body.variables() != null) {
            ProcessRestController.variablesOf(body.variables()).forEach(v -> set.put(v.name(), v.value()));
        }
        InputLimits.checkIdentifier(body.stepId(), "stepId");
        var type = BatchOperationApi.type(body.type());
        var parameters = type == io.mateu.workflow.domain.batch.BatchOperationType.MIGRATE
                ? io.mateu.workflow.domain.batch.BatchOperationParameters.migrate(body.targetVersion(), body.fromVersion(),
                body.stepMapping(), set, body.reason())
                : io.mateu.workflow.domain.batch.BatchOperationParameters.of(set, body.remove(), body.stepId(),
                body.reason());
        var created = batches.create(type, filterOf(body.filter()), parameters, caller());
        return ResponseEntity.accepted().location(URI.create("/workflow/api/batch-operations/" + created.id()))
                .body(BatchOperationView.of(created));
    }

    @GetMapping
    @Operation(operationId = "listBatchOperations", summary = "List batch operations", description = "The most recent first.")
    public List<BatchOperationView> list(@Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        verify(apiKey);
        return batches.list().stream().map(BatchOperationView::of).toList();
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getBatchOperation", summary = "Get a batch operation")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The batch and its progress."),
            @ApiResponse(responseCode = "404", description = "No such batch.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public BatchOperationView get(@Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                  @PathVariable("id") String id) {
        verify(apiKey);
        return BatchOperationView.of(batches.get(id).orElseThrow(() -> new BatchOperationNotFoundException(id)));
    }

    @PostMapping("/{id}/cancel")
    @Operation(operationId = "cancelBatchOperation", summary = "Cancel a batch operation",
            description = "No further process is touched; what was done stays done.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Cancelled."),
            @ApiResponse(responseCode = "404", description = "No such batch.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "It already finished.", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))})
    public BatchOperationView cancel(@Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                     @PathVariable("id") String id) {
        verify(apiKey);
        return BatchOperationView.of(batches.cancel(id));
    }

    @ExceptionHandler(BatchOperationNotFoundException.class)
    ProblemDetail onNotFound(BatchOperationNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(BatchOperationNotActiveException.class)
    ProblemDetail onNotActive(BatchOperationNotActiveException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
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

    /** The stored filter of a request's, checked as the listing checks its own parameters. */
    static BatchOperationFilter filterOf(BatchOperationApi.FilterBody body) {
        InputLimits.checkIdentifier(body.definitionId(), "definitionId");
        InputLimits.checkIdentifier(body.tag(), "tag");
        InputLimits.checkIdentifier(body.businessKey(), "businessKey");
        InputLimits.checkIdentifier(body.q(), "q");
        var conditions = new ArrayList<SearchAttributeFilter>();
        if (body.attributes() != null) {
            body.attributes().forEach((name, value) -> conditions.add(new SearchAttributeFilter(name, SearchAttributeFilter.Op.EQ, value)));
        }
        conditions.addAll(SearchQuery.parse(body.query()));
        SearchAttributeFilter.checked(conditions);
        return new BatchOperationFilter(body.definitionId(), body.tag(), ProcessRestController.statuses(body.status()),
                ProcessRestController.moment(body.from(), "from", false), ProcessRestController.moment(body.to(), "to", true),
                body.businessKey(), body.q(), Boolean.TRUE.equals(body.onlyErrors()),
                conditions.isEmpty() ? null : SearchQuery.format(conditions));
    }

    private void verify(String apiKey) {
        EngineApiAccess.verify(apiProperties, apiKey);
    }

    private AuthorizationContext caller() {
        var resolver = callerResolver.getIfAvailable();
        return resolver == null ? null : resolver.current();
    }
}
