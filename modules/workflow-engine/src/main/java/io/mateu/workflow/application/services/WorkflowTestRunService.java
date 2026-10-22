package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.TestRunMutex;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.ProcessSnapshotReader;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.usecases.process.create.CreateProcessCommand;
import io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.TestRunIds;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.input.InputLimits;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import io.mateu.workflow.security.FlowAuthorizationService;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Starts one manual instance from a validated document. Never publishes or edits a definition.
 *
 * <p>Every run gets a private definition id ({@link #definitionIdOf}), so it is never mistaken for an
 * instance of a published definition: catalogue lookups, listings and migrations do not reach it.
 */
public final class WorkflowTestRunService {
    private final ProcessRepository processes;
    private final WorkflowDefinitionRepository definitions;
    private final CreateProcessUseCase creator;
    private final TestRunMutex locks;
    private final ProcessSnapshotReader snapshots;
    private final boolean flowAuthorization;
    private final List<String> requiredRoles;

    public WorkflowTestRunService(ProcessRepository processes, WorkflowDefinitionRepository definitions,
                                 CreateProcessUseCase creator, TestRunMutex locks,
                                 ProcessSnapshotReader snapshots, boolean flowAuthorization,
                                 List<String> requiredRoles) {
        this.processes = processes;
        this.definitions = definitions;
        this.creator = creator;
        this.locks = locks;
        this.snapshots = snapshots;
        this.flowAuthorization = flowAuthorization;
        this.requiredRoles = requiredRoles == null ? List.of()
                : requiredRoles.stream().filter(role -> role != null && !role.isBlank()).map(String::trim).toList();
    }

    /** The private id a run executes under: {@code vscode-<runId>}, or {@code vscode-<id>-<runId>} for a named document. */
    public static String definitionIdOf(String documentId, String runId) {
        return TestRunIds.of(documentId, runId);
    }

    public record Started(Process process, boolean created) {}

    public Started start(String runId, WorkflowDefinition definition, List<Variable> variables,
                         String requestDigest, AuthorizationContext caller) {
        if (runId == null || !UUID.fromString(runId).toString().equals(runId))
            throw new IllegalArgumentException("runId must be a canonical UUID.");
        requireTestRunRole(caller);
        definition = definition.withId(definitionIdOf(definition.id(), runId));
        InputLimits.checkIdentifier(definition.id(), "definition.id");
        InputLimits.checkNamedValues(variables, Variable::name, Variable::value, "test run");
        definition.checkInvariants();
        if (definition.steps().stream().anyMatch(step -> step.type() == StepType.CONTINUE_AS_NEW
                && step.continueAsNew() != null && step.continueAsNew().latest()))
            throw new IllegalArgumentException("CONTINUE_AS_NEW latest is not available in a test run: "
                    + "the run has no published definition to continue on. Use 'same'.");
        if (definitions.findById(definition.id()).isPresent())
            throw new Conflict("A published definition already uses the test run id '" + definition.id() + "'.");
        authorize(definition, caller);
        var started = definition;
        var outcome = new AtomicReference<Started>();
        locks.run(runId, () -> {
            var previous = processes.findById(runId).orElse(null);
            if (previous != null) {
                if (!previous.isTestRun() || !Objects.equals(requestDigest, previous.getTestRunRequestDigest()))
                    throw new Conflict("runId already belongs to a different request. Use a new runId.");
                authorize(snapshots.read(previous.getWorkflowDefinitionJson()), caller);
                outcome.set(new Started(previous, false));
                return;
            }
            if (!started.status().accceptsNewInstances())
                throw new Conflict("The document does not accept new instances: " + started.status());
            creator.handle(new CreateProcessCommand(runId, started.id(), "ide-test:" + runId,
                    variables, null, caller, null, null, started, true, requestDigest));
            var created = processes.findById(runId).orElseThrow(() -> new Conflict("The test run could not be created."));
            outcome.set(new Started(created, true));
        });
        return outcome.get();
    }

    /**
     * A test run executes a document the caller wrote, against real workers and secrets, so it needs a
     * role the engine grants — never one the document declares — whether or not flow authorization is on.
     */
    private void requireTestRunRole(AuthorizationContext caller) {
        String subject = caller == null ? null : caller.subject();
        if (requiredRoles.isEmpty()) throw new FlowAuthorizationDeniedException("Test runs are refused until "
                + "workflow.test-runs.required-roles (or workflow.security.operations.required-roles) names a role.");
        var decision = FlowAuthorizationService.authorize(caller, List.of(), requiredRoles);
        if (caller == null || !decision.allowed())
            throw FlowAuthorizationDeniedException.of("run workflow test", subject, List.of(), decision.missingRoles());
    }

    private void authorize(WorkflowDefinition definition, AuthorizationContext caller) {
        if (!flowAuthorization) return;
        var decision = FlowAuthorizationService.authorize(caller, definition.requiredScopes(), definition.requiredRoles());
        if (!decision.allowed()) throw FlowAuthorizationDeniedException.of("run workflow test",
                caller == null ? null : caller.subject(), decision.missingScopes(), decision.missingRoles());
    }

    public static final class Conflict extends RuntimeException {
        public Conflict(String message) { super(message); }
    }
}
