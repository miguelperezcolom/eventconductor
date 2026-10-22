package io.mateu.workflow.infra.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.ProcessSnapshotReader;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.application.services.WorkflowDefinitionValidator;
import io.mateu.workflow.application.services.WorkflowTestRunService;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.infra.in.monitoring.ProcessGraphProjection;
import io.mateu.workflow.input.InputLimits;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.CallerResolver;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Manual IDE runs and read-only monitoring, using the engine's normal execution pipeline. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/workflow/api")
@Tag(name = "Workflow execution", description = "Opt-in test runs and frozen process graphs.")
public class WorkflowExecutionController {
    private final WorkflowTestRunService tests;
    private final ProcessOperationsService processes;
    private final ProcessRepository repository;
    private final StepExecutionRepository steps;
    private final ProcessSnapshotReader snapshots;
    private final WorkflowDefinitionValidator validator;
    private final MessageApiProperties access;
    private final ObjectProvider<CallerResolver> callers;
    private final boolean enabled;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public WorkflowExecutionController(WorkflowTestRunService tests, ProcessOperationsService processes,
                                       ProcessRepository repository, StepExecutionRepository steps,
                                       ProcessSnapshotReader snapshots,
                                       WorkflowDefinitionValidator validator, MessageApiProperties access,
                                       ObjectProvider<CallerResolver> callers,
                                       @Value("${workflow.test-runs.enabled:false}") boolean enabled) {
        this.tests = tests; this.processes = processes; this.repository = repository; this.steps = steps;
        this.snapshots = snapshots; this.validator = validator;
        this.access = access; this.callers = callers; this.enabled = enabled;
    }

    public record TestRunRequest(String runId, @Schema(type = "object") Map<String, Object> definition,
                                 Map<String, Object> variables) {}

    public record GraphView(ProcessApi.ProcessView process, boolean testRun, String parentProcessId,
                            @Schema(type = "object", description = "Frozen graph topology; request, credential and prompt blocks are omitted.")
                            Map<String, Object> definition, Map<String, Object> overlay, List<ProcessApi.StepView> steps) {}

    public record ChildProcessView(String id, String parentStepExecutionId, String stepId, Integer itemIndex,
                                   int iteration, String definitionId, int definitionVersion, String name,
                                   String status, String created, String continuedFrom, String continuedAs) {}
    public record ChildrenPage(List<ChildProcessView> content, int page, int size, long totalElements, int totalPages) {}

    @PostMapping("/test-runs")
    @Operation(operationId = "startWorkflowTestRun", summary = "Run a document without publishing it",
            description = "Requires workflow.test-runs.enabled=true (off by default) and a caller holding"
                    + " workflow.test-runs.required-roles (falls back to workflow.security.operations.required-roles;"
                    + " none configured is 403). Calls real workers. Runs under a private definition id,"
                    + " vscode-<id>-<runId>, never the published one."
                    + " Idempotent on runId; reusing it with another document or input is 409."
                    + " Manual start only: no schedules, triggers or startPolicy admission is registered.")
    public ResponseEntity<ProcessApi.ProcessView> start(
            @Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestBody TestRunRequest body) throws Exception {
        EngineApiAccess.verify(access, apiKey);
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "IDE test runs are disabled. Set workflow.test-runs.enabled=true on a test engine.");
        if (body == null || body.definition() == null)
            throw new IllegalArgumentException("definition must be a workflow JSON object.");
        if (body.runId() == null || !UUID.fromString(body.runId()).toString().equals(body.runId()))
            throw new IllegalArgumentException("runId must be a canonical UUID.");
        ObjectNode document = json.valueToTree(body.definition());
        InputLimits.checkText(document.toString(), "workflow definition");
        validator.validateSource(document, "VS Code test run");
        ObjectNode source = document.deepCopy();
        String id = source.path("id").asText("");
        if (id.isBlank()) { id = "vscode-" + body.runId(); source.put("id", id); }
        final String definitionId = id;
        var definition = json.treeToValue(source, WorkflowDefinition.class);
        definition = definition.withSteps(definition.steps().stream()
                .map(step -> step.withWorkflowDefinitionId(definitionId)).toList());
        validator.validate(definition);
        try { definition.checkInvariants(); }
        catch (IllegalStateException error) { throw new IllegalArgumentException(error.getMessage(), error); }
        String request = document + "\n" + json.writeValueAsString(body.variables() == null ? Map.of() : body.variables());
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.getBytes(StandardCharsets.UTF_8)));
        var result = tests.start(body.runId(), definition, ProcessRestController.variablesOf(body.variables()), digest, caller());
        var response = result.created() ? ResponseEntity.created(URI.create("/workflow/api/processes/" + result.process().getId()))
                : ResponseEntity.ok().location(URI.create("/workflow/api/processes/" + result.process().getId()));
        return response.body(ProcessApi.ProcessView.of(result.process()));
    }

    @GetMapping("/processes/{id}/graph")
    @Operation(operationId = "getProcessGraph", summary = "Frozen graph, runtime steps and masked execution state")
    public GraphView graph(@Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                            @PathVariable String id) {
        EngineApiAccess.verify(access, apiKey);
        var caller = caller();
        var process = processes.get(id, caller);
        var executions = processes.steps(id, caller);
        var logs = processes.logs(id, caller);
        if (process.getWorkflowDefinitionJson() == null || process.getWorkflowDefinitionJson().isBlank())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Process '" + id + "' has no frozen definition snapshot, so its graph cannot be drawn.");
        var projection = ProcessGraphProjection.project(snapshots.read(process.getWorkflowDefinitionJson()), process, executions, logs);
        String parent = process.getParentStepExecutionId() == null ? null
                : steps.findById(process.getParentStepExecutionId()).map(step -> step.getProcessId()).orElse(null);
        return new GraphView(ProcessApi.ProcessView.of(process), process.isTestRun(), parent,
                displayDefinition(projection.definition()), projection.overlay(), executions.stream().map(ProcessApi.StepView::of).toList());
    }

    @GetMapping("/processes/{id}/children")
    @Operation(operationId = "getProcessChildren", summary = "Direct child runs, linked to their parent step execution",
            description = "Includes earlier attempts, iterations and continuations. Paged oldest first; no recursive traversal."
                    + " A page holding a child the caller may not read is refused whole with 403, so that"
                    + " totalElements never counts runs the caller cannot see.")
    public ChildrenPage children(@Parameter(hidden = true) @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                  @PathVariable String id, @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String stepId) {
        EngineApiAccess.verify(access, apiKey);
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("page must be nonnegative; size must be 1–100.");
        var caller = caller();
        var executions = processes.steps(id, caller);
        var byId = executions.stream().collect(Collectors.toMap(step -> step.id(), Function.identity()));
        InputLimits.checkIdentifier(stepId, "stepId");
        var selectedIds = executions.stream().filter(step -> stepId == null || stepId.equals(step.getStepId()))
                .map(step -> step.id()).collect(Collectors.toSet());
        var found = repository.findChildren(id, selectedIds, stepId, page, size);
        var content = new ArrayList<ChildProcessView>();
        for (var child : found.content()) {
            processes.authorizeRead(child, caller);
            var source = byId.get(child.getParentStepExecutionId());
            content.add(new ChildProcessView(child.getId(), child.getParentStepExecutionId(),
                    source == null ? null : source.getStepId(), source == null ? null : source.getItemIndex(),
                    source == null ? 0 : source.getIteration(), child.getWorkflowDefinitionId(), child.getWorkflowDefinitionVersion(),
                    child.getName(), child.getStatus().name(), child.getCreated() == null ? null : child.getCreated().toString(),
                    child.getContinuedFromProcessId(), child.getContinuedAsProcessId()));
        }
        return new ChildrenPage(content, page, size, found.totalElements(), (int) ((found.totalElements() + size - 1) / size));
    }

    /** A graph is topology, not a way to reveal HTTP headers, LLM prompts or agent input payloads. */
    private Map<String, Object> displayDefinition(WorkflowDefinition definition) {
        ObjectNode node = json.valueToTree(definition);
        node.retain("id", "name", "version", "description", "steps", "layout");
        for (var step : node.withArray("steps")) {
            var agent = step.path("agent");
            ((ObjectNode) step).retain("id", "name", "type", "description", "preconditionStepId", "preconditionStepIds",
                    "preconditions", "preconditionExpression", "parallel", "compensable", "compensationStepId",
                    "onTimeoutStepId", "maxSuccessfulExecutions", "joinType", "repeat", "loopBack", "forEach", "childWorkflowDefinitionId");
            if (agent.isObject() && agent.hasNonNull("ref"))
                ((ObjectNode) step).set("agent", json.createObjectNode().set("ref", agent.get("ref")));
        }
        return json.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    private AuthorizationContext caller() {
        var resolver = callers.getIfAvailable();
        return resolver == null ? null : resolver.current();
    }

    @ExceptionHandler(ProcessOperationsService.ProcessNotFoundException.class)
    ProblemDetail missing(RuntimeException error) { return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, error.getMessage()); }
    @ExceptionHandler(FlowAuthorizationDeniedException.class)
    ProblemDetail denied(RuntimeException error) { return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, error.getMessage()); }
    @ExceptionHandler({WorkflowTestRunService.Conflict.class, io.mateu.workflow.application.out.ConcurrentProcessAccessException.class})
    ProblemDetail conflict(RuntimeException error) { return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, error.getMessage()); }
    @ExceptionHandler({IllegalArgumentException.class, InputLimits.InputRejectedException.class,
            io.mateu.workflow.application.services.WorkflowDefinitionValidator.WorkflowDefinitionValidationException.class,
            com.fasterxml.jackson.core.JsonProcessingException.class})
    ProblemDetail invalid(Exception error) { return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getMessage()); }
    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail status(ResponseStatusException error) { return ProblemDetail.forStatusAndDetail(error.getStatusCode(), error.getReason()); }
}
