package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.out.ProcessListingFilter;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.ProcessSnapshotReader;
import io.mateu.workflow.application.out.ProcessSummaryPage;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.UnknownWorkflowDefinitionException;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase;
import io.mateu.workflow.application.usecases.process.create.CreateProcessCommand;
import io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase;
import io.mateu.workflow.application.usecases.process.pause.PauseProcessUseCase;
import io.mateu.workflow.application.usecases.process.restart.RestartProcessUseCase;
import io.mateu.workflow.application.usecases.process.resume.ResumeProcessUseCase;
import io.mateu.workflow.application.usecases.process.retry.RetryProcessUseCase;
import io.mateu.workflow.application.usecases.stepexecution.retry.RetryStepExecutionUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.dtos.TraceContext;
import io.mateu.workflow.dtos.events.domain.ProcessCancellationRequested;
import io.mateu.workflow.dtos.events.integration.PauseProcessRequested;
import io.mateu.workflow.dtos.events.integration.RestartProcessRequested;
import io.mateu.workflow.dtos.events.integration.ResumeProcessRequested;
import io.mateu.workflow.dtos.events.integration.RetryProcessRequested;
import io.mateu.workflow.dtos.events.integration.RetryStepExecutionRequested;
import io.mateu.workflow.input.InputLimits;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import io.mateu.workflow.security.FlowAuthorizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Processes as a resource: start one, read it, list them, and ask for the operator commands —
 * cancel, pause, resume, retry, restart, retry a step — for the doors that answer a caller
 * synchronously (the REST API, {@code /workflow/api/processes}).
 *
 * <p>Nothing here is a second implementation. A start is {@link CreateProcessUseCase} — the same
 * call the admin console's "new process" form makes, with its idempotency, its disabled-definition
 * rule and its flow-authorization gate. A listing is {@link ProcessListingService}, which the console
 * lists through too. A command is the same {@code *Requested} event the console and the MCP tools
 * dispatch through {@link CommandDispatcher}, so it runs on the node that owns the process, under its
 * lock, by the same use case. What this adds is what a synchronous caller needs and an
 * event cannot give: a {@code 404} for a process that does not exist, and a {@code 409} for one whose
 * status the use case would ignore the command in — decided by the use case's own rule
 * ({@code acceptsFrom}), not a copy of it.
 *
 * <p><b>Flow authorization.</b> With {@code workflow.security.flow-authorization.enabled}, a definition's
 * {@code requiredScopes}/{@code requiredRoles} gate starting it (in the use case, as everywhere) and,
 * here, every read of and command on one of its processes: a caller who may not start a workflow
 * may not read its variables or cancel its runs either. The listing is not filtered — it carries no
 * variables, only what identifies a process and its status.
 */
@Service
@Slf4j
public class ProcessOperationsService {

    private final ProcessRepository processRepository;
    private final StepExecutionRepository stepExecutionRepository;
    private final LogMessageRepository logMessageRepository;
    private final WorkflowDefinitionRepository workflowDefinitionRepository;
    private final CreateProcessUseCase createProcessUseCase;
    private final CommandDispatcher commandDispatcher;
    private final ProcessListingService listing;
    private final ProcessStatusAnnouncer readModel;
    private final ObjectProvider<ProcessIndexRepository> processIndex;
    private final boolean flowAuthorizationEnabled;
    private final io.mateu.workflow.application.usecases.process.operate.ProcessOperationRules rules;
    private final List<String> operationsRequiredRoles;
    private final ObjectProvider<ProcessSnapshotReader> testRunSnapshots;

    /** Without the manual operations' own settings: their rules as the engine has them, no extra role. */
    public ProcessOperationsService(ProcessRepository processRepository,
                                    StepExecutionRepository stepExecutionRepository,
                                    LogMessageRepository logMessageRepository,
                                    WorkflowDefinitionRepository workflowDefinitionRepository,
                                    CreateProcessUseCase createProcessUseCase,
                                    CommandDispatcher commandDispatcher,
                                    ProcessListingService listing,
                                    ProcessStatusAnnouncer readModel,
                                    ObjectProvider<ProcessIndexRepository> processIndex,
                                    boolean flowAuthorizationEnabled) {
        this(processRepository, stepExecutionRepository, logMessageRepository, workflowDefinitionRepository,
                createProcessUseCase, commandDispatcher, listing, readModel, processIndex, flowAuthorizationEnabled,
                new io.mateu.workflow.application.usecases.process.operate.ProcessOperationRules(
                        new io.mateu.workflow.domain.services.CompensationService()),
                List.of());
    }

    public ProcessOperationsService(ProcessRepository processRepository,
                                    StepExecutionRepository stepExecutionRepository,
                                    LogMessageRepository logMessageRepository,
                                    WorkflowDefinitionRepository workflowDefinitionRepository,
                                    CreateProcessUseCase createProcessUseCase,
                                    CommandDispatcher commandDispatcher,
                                    ProcessListingService listing,
                                    ProcessStatusAnnouncer readModel,
                                    ObjectProvider<ProcessIndexRepository> processIndex,
                                    boolean flowAuthorizationEnabled,
                                    io.mateu.workflow.application.usecases.process.operate.ProcessOperationRules rules,
                                    List<String> operationsRequiredRoles) {
        this(processRepository, stepExecutionRepository, logMessageRepository, workflowDefinitionRepository,
                createProcessUseCase, commandDispatcher, listing, readModel, processIndex, flowAuthorizationEnabled,
                rules, operationsRequiredRoles, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProcessOperationsService(ProcessRepository processRepository,
                                    StepExecutionRepository stepExecutionRepository,
                                    LogMessageRepository logMessageRepository,
                                    WorkflowDefinitionRepository workflowDefinitionRepository,
                                    CreateProcessUseCase createProcessUseCase,
                                    CommandDispatcher commandDispatcher,
                                    ProcessListingService listing,
                                    ProcessStatusAnnouncer readModel,
                                    ObjectProvider<ProcessIndexRepository> processIndex,
                                    @Value("${workflow.security.flow-authorization.enabled:false}")
                                    boolean flowAuthorizationEnabled,
                                    io.mateu.workflow.application.usecases.process.operate.ProcessOperationRules rules,
                                    @Value("${workflow.security.operations.required-roles:}")
                                    List<String> operationsRequiredRoles,
                                    ObjectProvider<ProcessSnapshotReader> testRunSnapshots) {
        this.testRunSnapshots = testRunSnapshots;
        this.processRepository = processRepository;
        this.stepExecutionRepository = stepExecutionRepository;
        this.logMessageRepository = logMessageRepository;
        this.workflowDefinitionRepository = workflowDefinitionRepository;
        this.createProcessUseCase = createProcessUseCase;
        this.commandDispatcher = commandDispatcher;
        this.listing = listing;
        this.readModel = readModel;
        this.processIndex = processIndex;
        this.flowAuthorizationEnabled = flowAuthorizationEnabled;
        this.rules = rules;
        this.operationsRequiredRoles = operationsRequiredRoles == null ? List.of()
                : operationsRequiredRoles.stream().filter(role -> role != null && !role.isBlank()).map(String::trim).toList();
    }

    /** Apply the frozen test's requirements as well as any published requirements under its id. */
    public void authorizeRead(Process process, AuthorizationContext caller) {
        authorize(process.getWorkflowDefinitionId(), caller, "read process '" + process.getId() + "'");
        if (!flowAuthorizationEnabled || !process.isTestRun()) return;
        var reader = testRunSnapshots == null ? null : testRunSnapshots.getIfAvailable();
        if (reader == null) throw new IllegalStateException("Test run snapshot reader is unavailable.");
        var snapshot = reader.read(process.getWorkflowDefinitionJson());
        var decision = FlowAuthorizationService.authorize(caller, snapshot.requiredScopes(), snapshot.requiredRoles());
        if (!decision.allowed()) throw FlowAuthorizationDeniedException.of("read test run", caller == null ? null : caller.subject(),
                decision.missingScopes(), decision.missingRoles());
    }

    /** A test run's frozen requirements; reuses the process {@link #ref} already loaded. */
    private void authorizeExistingTest(ProcessRef process, AuthorizationContext caller) {
        if (process.local() != null && process.local().isTestRun()) authorizeRead(process.local(), caller);
    }

    /** A test run belongs to no published version, so it has nothing to migrate onto. */
    private static void refuseTestRunMigration(ProcessRef process, String operation) {
        if (process.local() != null && process.local().isTestRun())
            throw new ProcessStateConflictException(process.id(), operation, process.status(),
                    "a test run is not migrated; start a new one from the edited document");
    }

    // ── Start ────────────────────────────────────────────────────────────────────────────────────

    /** The outcome of a start: the process, and whether this call created it or found it already there. */
    public record StartOutcome(Process process, boolean created,
                               io.mateu.workflow.application.flowcontrol.FlowControlService.Decision flowControl) {

        public StartOutcome(Process process, boolean created) {
            this(process, created, null);
        }
    }

    /** A start the definition's {@code startPolicy} dropped (over a throttle): the caller may try again at {@link #retryAt()}. */
    public static class StartDroppedException extends RuntimeException {
        private final java.time.LocalDateTime retryAt;

        public StartDroppedException(String message, java.time.LocalDateTime retryAt) {
            super(message);
            this.retryAt = retryAt;
        }

        public java.time.LocalDateTime retryAt() {
            return retryAt;
        }
    }

    /** Flow control of API starts; a provider so hand-built instances (tests) work without it. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    ObjectProvider<io.mateu.workflow.application.flowcontrol.FlowControlService> flowControl;

    /**
     * Starts a process of {@code definitionId}, or — when {@code businessKey} already names a process
     * of the same definition — answers with that one ({@code created = false}): a retried request
     * never starts a second process. A key taken by another definition's process is a conflict.
     *
     * @throws InputLimits.InputRejectedException   over the engine's input limits
     * @throws UnknownWorkflowDefinitionException    no such definition
     * @throws NotAcceptingInstancesException        the definition is disabled or archived
     * @throws BusinessKeyTakenException             the key names a process of another definition
     * @throws FlowAuthorizationDeniedException      the caller may not start the definition
     */
    public StartOutcome start(String definitionId, String businessKey, List<Variable> variables,
                              AuthorizationContext caller, TraceContext traceContext) {
        if (definitionId == null || definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId is required.");
        }
        InputLimits.checkIdentifier(definitionId, "definitionId");
        InputLimits.checkIdentifier(businessKey, "businessKey");
        InputLimits.checkNamedValues(variables, Variable::name, Variable::value, "this process");
        var key = businessKey == null || businessKey.isBlank() ? null : businessKey;

        var definition = workflowDefinitionRepository.findById(definitionId)
                .orElseThrow(() -> new UnknownWorkflowDefinitionException(definitionId));
        if (key != null) {
            var existing = processRepository.findByBusinessKey(key);
            // The existing run answers (200) unless the definition's businessKeyPolicy lets a new run take the
            // key over — which the creation below then does (cancelling a running holder for terminateRunning).
            if (existing.isPresent() && (!definitionId.equals(existing.get().getWorkflowDefinitionId())
                    || definition.keyPolicy().decide(existing.get().getStatus())
                    == io.mateu.workflow.domain.aggregates.BusinessKeyPolicy.Decision.KEEP)) {
                return replay(existing.get(), definitionId, caller);
            }
        }
        if (!definition.status().accceptsNewInstances()) {
            // The use case would ignore the creation; a caller waiting for an answer is told why.
            throw new NotAcceptingInstancesException(definitionId, definition.status().name());
        }

        var processId = UUID.randomUUID().toString();
        var policy = definition.startPolicy();
        var flow = flowControl == null ? null : flowControl.getIfAvailable();
        if (policy != null && flow != null) {
            return startUnderPolicy(flow, definition, processId, key, variables, caller, traceContext);
        }
        createProcessUseCase.handle(new CreateProcessCommand(processId, definitionId, key,
                variables == null ? List.of() : variables, null, caller, traceContext));
        var created = processRepository.findById(processId);
        if (created.isPresent()) {
            return new StartOutcome(created.get(), true);
        }
        // Not created: another request took the key between the look-up and the creation (the use
        // case dedupes on it), or the definition stopped accepting instances in between.
        if (key != null) {
            var winner = processRepository.findByBusinessKey(key);
            if (winner.isPresent()) {
                return replay(winner.get(), definitionId, caller);
            }
        }
        throw new NotAcceptingInstancesException(definitionId, definition.status().name());
    }

    /**
     * A start under the definition's {@code startPolicy}: started now (201), the running one for a singleton
     * that skips (200), held back by a debounce or a throttle queue (202, no process yet), or dropped
     * ({@link StartDroppedException}, 429).
     */
    private StartOutcome startUnderPolicy(io.mateu.workflow.application.flowcontrol.FlowControlService flow,
                                          io.mateu.workflow.domain.aggregates.WorkflowDefinition definition,
                                          String processId, String key, List<Variable> variables,
                                          AuthorizationContext caller, TraceContext traceContext) {
        // Judged before anything is held back: a start the caller may not make must not wait to be refused.
        CreateProcessUseCase.refuseIfCallerMayNotStart(definition, caller, key);
        var values = new java.util.LinkedHashMap<String, String>();
        (variables == null ? List.<Variable>of() : variables).forEach(v -> values.put(v.name(), v.value()));
        String flowKey;
        try {
            flowKey = io.mateu.workflow.application.flowcontrol.FlowKeys.overVariables(definition.startPolicy(),
                    definition.id(), key, values);
        } catch (io.mateu.workflow.application.flowcontrol.FlowKeys.UnrenderableKeyException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        var start = new io.mateu.workflow.application.flowcontrol.FlowStart(
                io.mateu.workflow.application.flowcontrol.FlowStart.startScope(definition.id()), flowKey, definition.id(),
                key, values, caller, io.mateu.workflow.application.flowcontrol.FlowStart.START, null,
                definition.startPolicy(), false);
        var decision = flow.admit(start, s -> {
            createProcessUseCase.handle(new CreateProcessCommand(processId, definition.id(), s.businessKey(),
                    variables == null ? List.of() : variables, null, caller, traceContext));
            return new io.mateu.workflow.application.flowcontrol.FlowControlService.Started(processId, s.businessKey());
        }, io.mateu.workflow.application.flowcontrol.FlowControlService.Mode.DEFAULT);
        return switch (decision.outcome()) {
            case STARTED, REPLACED -> {
                var created = processRepository.findById(processId);
                if (created.isEmpty()) {
                    throw new NotAcceptingInstancesException(definition.id(), definition.status().name());
                }
                yield new StartOutcome(created.get(), true, decision);
            }
            case SKIPPED -> {
                var running = decision.processId() != null ? processRepository.findById(decision.processId())
                        : processRepository.findByBusinessKey(decision.businessKey());
                yield new StartOutcome(running.orElse(null), false, decision);
            }
            case DEBOUNCED, THROTTLED -> new StartOutcome(null, false, decision);
            case DROPPED -> throw new StartDroppedException("Start of workflow '" + definition.id() + "' "
                    + decision.detail() + (flowKey.isEmpty() ? "" : " for key '" + flowKey + "'") + ".", decision.dueAt());
        };
    }

    private StartOutcome replay(Process existing, String definitionId, AuthorizationContext caller) {
        if (!definitionId.equals(existing.getWorkflowDefinitionId())) {
            throw new BusinessKeyTakenException(existing.getBusinessKey(), existing.getWorkflowDefinitionId());
        }
        if (existing.isTestRun()) authorizeRead(existing, caller);
        else authorize(existing.getWorkflowDefinitionId(), caller, "start workflow '" + definitionId + "'");
        return new StartOutcome(existing, false);
    }

    // ── Reads ────────────────────────────────────────────────────────────────────────────────────

    /** The process with this id. @throws ProcessNotFoundException when there is none on this node */
    public Process get(String processId, AuthorizationContext caller) {
        var process = processRepository.findById(processId)
                .orElseThrow(() -> new ProcessNotFoundException("No process with id '" + processId + "'."));
        authorizeRead(process, caller);
        return process;
    }

    /** The process with this business key. @throws ProcessNotFoundException when there is none */
    public Process getByBusinessKey(String businessKey, AuthorizationContext caller) {
        var process = processRepository.findByBusinessKey(businessKey)
                .orElseThrow(() -> new ProcessNotFoundException("No process with business key '" + businessKey + "'."));
        authorizeRead(process, caller);
        return process;
    }

    /** One page of the listing, as the admin console lists it — see {@link ProcessListingService}. */
    public ProcessSummaryPage list(ProcessListingFilter filter, int page, int size) {
        return listing.search(filter, page, size);
    }

    /** The ids of the definitions carrying {@code tag}, for a listing filter. */
    public java.util.Set<String> definitionsTagged(String tag) {
        return listing.definitionsTagged(tag);
    }

    /** The process's step executions, in the order they were created. */
    public List<StepExecution> steps(String processId, AuthorizationContext caller) {
        var process = get(processId, caller);
        return stepExecutionRepository.findByProcess(process).stream()
                .sorted(Comparator.comparingLong(StepExecution::getOrder))
                .toList();
    }

    /** What the process logged, oldest first. */
    public List<LogMessage> logs(String processId, AuthorizationContext caller) {
        get(processId, caller);
        return logMessageRepository.findByProcessId(processId).stream()
                .sorted(Comparator.comparing(LogMessage::getTimestamp,
                        Comparator.nullsFirst(Comparator.<java.time.LocalDateTime>naturalOrder())))
                .toList();
    }

    // ── Commands ─────────────────────────────────────────────────────────────────────────────────

    public void cancel(String processId, AuthorizationContext caller) {
        request(processId, "cancel", CancelProcessUseCase::acceptsFrom,
                id -> new ProcessCancellationRequested(null, id), caller);
    }

    public void pause(String processId, AuthorizationContext caller) {
        request(processId, "pause", PauseProcessUseCase::acceptsFrom, PauseProcessRequested::new, caller);
    }

    public void resume(String processId, AuthorizationContext caller) {
        request(processId, "resume", ResumeProcessUseCase::acceptsFrom, ResumeProcessRequested::new, caller);
    }

    public void retry(String processId, AuthorizationContext caller) {
        request(processId, "retry", RetryProcessUseCase::acceptsFrom, RetryProcessRequested::new, caller);
    }

    public void restart(String processId, AuthorizationContext caller) {
        request(processId, "restart", RestartProcessUseCase::acceptsFrom, RestartProcessRequested::new, caller);
    }

    /**
     * Runs one failed (ERROR or TIMEOUT) step execution of the process again.
     *
     * @throws ProcessNotFoundException      no such process, or the step is not one of its steps
     * @throws ProcessStateConflictException the step did not fail, or the process is finished
     */
    public void retryStep(String processId, String stepExecutionId, AuthorizationContext caller) {
        var process = ref(processId);
        var step = stepExecutionRepository.findById(stepExecutionId)
                .filter(found -> processId.equals(found.getProcessId()))
                .orElseThrow(() -> new ProcessNotFoundException("Process '" + processId
                        + "' has no step execution '" + stepExecutionId + "'."));
        authorize(process.workflowDefinitionId(), caller, "retry a step of process '" + processId + "'");
        authorizeExistingTest(process, caller);
        if (!RetryStepExecutionUseCase.retriesStepFrom(step.getStatus())) {
            throw new ProcessStateConflictException(processId, "retry step '" + stepExecutionId + "'",
                    process.status(), "the step is " + step.getStatus() + "; only ERROR or TIMEOUT steps are retried");
        }
        if (!RetryStepExecutionUseCase.retriesStepOfProcessIn(process.status())) {
            throw new ProcessStateConflictException(processId, "retry step '" + stepExecutionId + "'",
                    process.status(), "a finished process is not revived");
        }
        commandDispatcher.dispatch(new RetryStepExecutionRequested(stepExecutionId, processId));
    }

    // ── Manual operations (instance modification) ────────────────────────────────────────────────

    /**
     * Changes the variables of a live (RUNNING, PAUSED or ERROR) process: writes {@code set}, deletes
     * {@code remove}. Carried out by {@code SetProcessVariablesUseCase} on the node that owns the process.
     *
     * @throws IllegalArgumentException      no reason, or nothing to change
     * @throws ProcessNotFoundException      no such process
     * @throws ProcessStateConflictException the process is not live
     * @throws FlowAuthorizationDeniedException the caller may not operate on it
     */
    public void setVariables(String processId, List<Variable> set, List<String> remove, String reason,
                             AuthorizationContext caller) {
        requireReason(reason);
        var toSet = set == null ? List.<Variable>of() : set;
        var toRemove = remove == null ? List.<String>of() : remove;
        if (toSet.isEmpty() && toRemove.isEmpty()) {
            throw new IllegalArgumentException("Nothing to change: give variables to set or names to remove.");
        }
        InputLimits.checkNamedValues(toSet, Variable::name, Variable::value, "a change of process variables");
        toSet.forEach(variable -> {
            if (variable.name() == null || variable.name().isBlank()) {
                throw new IllegalArgumentException("A variable to set needs a name.");
            }
        });
        toRemove.forEach(name -> InputLimits.checkIdentifier(name, "a variable to remove"));
        var process = ref(processId);
        authorizeOperation(process.workflowDefinitionId(), caller, "change the variables of process '" + processId + "'");
        authorizeExistingTest(process, caller);
        var local = process.local();
        var why = local != null ? rules.whyNotSetVariables(local) : liveOrWhy(process.status());
        if (why != null) {
            throw new ProcessStateConflictException(processId, "change the variables of", process.status(), why);
        }
        commandDispatcher.dispatch(new io.mateu.workflow.dtos.events.integration.SetProcessVariablesRequested(processId,
                io.mateu.workflow.application.usecases.process.operate.OperationVariables.toWire(toSet), List.copyOf(toRemove),
                reason.strip(), subject(caller)));
    }

    /**
     * Completes one step execution of a live process by hand, as if its worker had answered COMPLETED
     * with {@code outputVariables}. Carried out by {@code CompleteStepManuallyUseCase}.
     *
     * @throws ProcessNotFoundException      no such process, or no such step execution of it
     * @throws ProcessStateConflictException the process or the step is not in a status it is done from
     */
    public void completeStep(String processId, String stepExecutionId, List<Variable> outputVariables, String reason,
                             AuthorizationContext caller) {
        requireReason(reason);
        var outputs = outputVariables == null ? List.<Variable>of() : outputVariables;
        InputLimits.checkNamedValues(outputs, Variable::name, Variable::value, "a manual step completion");
        InputLimits.checkIdentifier(stepExecutionId, "stepExecutionId");
        var process = ref(processId);
        authorizeOperation(process.workflowDefinitionId(), caller, "complete a step of process '" + processId + "'");
        authorizeExistingTest(process, caller);
        var local = process.local();
        if (local != null) {
            var step = stepExecutionRepository.findById(stepExecutionId)
                    .filter(found -> processId.equals(found.getProcessId()))
                    .orElseThrow(() -> new ProcessNotFoundException("Process '" + processId
                            + "' has no step execution '" + stepExecutionId + "'."));
            var why = rules.whyNotCompleteStep(local, step, stepExecutionRepository.findByProcess(local));
            if (why != null) {
                throw new ProcessStateConflictException(processId, "complete step '" + stepExecutionId + "' of",
                        process.status(), why);
            }
        } else {
            var why = liveOrWhy(process.status());
            if (why != null) {
                throw new ProcessStateConflictException(processId, "complete a step of", process.status(), why);
            }
        }
        commandDispatcher.dispatch(new io.mateu.workflow.dtos.events.integration.CompleteStepManuallyRequested(processId,
                stepExecutionId, io.mateu.workflow.application.usecases.process.operate.OperationVariables.toWire(outputs),
                reason.strip(), subject(caller)));
    }

    /**
     * Runs a live process again from one of its steps: that step and everything downstream of it are
     * reopened. Carried out by {@code RetryFromStepUseCase}.
     *
     * @param stepId the definition's step id, not a step execution's
     * @throws ProcessNotFoundException      no such process, or it has no such step
     * @throws ProcessStateConflictException the retry is refused — see {@code ProcessOperationRules#planRetryFrom}
     */
    public void retryFromStep(String processId, String stepId, String reason, AuthorizationContext caller) {
        requireReason(reason);
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId is required.");
        }
        InputLimits.checkIdentifier(stepId, "stepId");
        var process = ref(processId);
        authorizeOperation(process.workflowDefinitionId(), caller, "retry process '" + processId + "' from a step");
        authorizeExistingTest(process, caller);
        var local = process.local();
        if (local != null) {
            var steps = stepExecutionRepository.findByProcess(local);
            if (steps.stream().noneMatch(se -> stepId.equals(se.getStepId()))) {
                throw new ProcessNotFoundException("Process '" + processId + "' has no step '" + stepId + "'.");
            }
            try {
                rules.planRetryFrom(local, stepId, steps);
            } catch (io.mateu.workflow.application.usecases.process.operate.ProcessOperationRules.Refused refused) {
                throw new ProcessStateConflictException(processId, "retry from step '" + stepId + "'", process.status(),
                        refused.getMessage());
            }
        } else {
            var why = liveOrWhy(process.status());
            if (why != null) {
                throw new ProcessStateConflictException(processId, "retry from step '" + stepId + "'", process.status(), why);
            }
        }
        commandDispatcher.dispatch(new io.mateu.workflow.dtos.events.integration.RetryFromStepRequested(processId, stepId,
                reason.strip(), subject(caller)));
    }

    // ── Migration (2.31) ──────────────────────────────────────────────────────────────────────────

    private io.mateu.workflow.application.usecases.process.operate.MigrationPlanner migrationPlanner;

    /** Optional, so the engine's narrower test wirings need not build it; every application has it. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMigrationPlanner(io.mateu.workflow.application.usecases.process.operate.MigrationPlanner migrationPlanner) {
        this.migrationPlanner = migrationPlanner;
    }

    /**
     * What a migration of the process would do, without doing it: the effective mapping, every step's fate
     * and the problems — any error refuses it. Planned on this node from the process as it stands here.
     *
     * @param targetVersion null for the definition as it is now
     * @throws ProcessNotFoundException      no such process
     * @throws ProcessStateConflictException the process lives on another node (a dry run needs its steps)
     * @throws IllegalArgumentException      no such version
     */
    public MigrationPreview dryRunMigration(String processId, Integer targetVersion, java.util.Map<String, String> stepMapping,
                                            java.util.Map<String, String> variableMapping, AuthorizationContext caller) {
        checkMigrationInput(stepMapping, variableMapping);
        var process = ref(processId);
        authorizeOperation(process.workflowDefinitionId(), caller, "plan the migration of process '" + processId + "'");
        authorizeExistingTest(process, caller);
        refuseTestRunMigration(process, "plan the migration of");
        var local = process.local();
        if (local == null || migrationPlanner == null) {
            throw new ProcessStateConflictException(processId, "plan the migration of", process.status(),
                    "it is owned by another node, which is where its migration is planned");
        }
        var planned = migrationPlanner.plan(local, stepExecutionRepository.findByProcess(local), targetVersion,
                stepMapping, variableMapping);
        return new MigrationPreview(processId, local.getWorkflowDefinitionId(), local.getWorkflowDefinitionVersion(),
                planned.target().version(), planned.plan());
    }

    /**
     * Moves a live process onto another version of its definition. Planned here first — a plan with an
     * error is refused with its problems — and carried out by {@code MigrateProcessUseCase} on the node that
     * owns the process, where it is planned again.
     *
     * @throws IllegalArgumentException      no reason, a malformed mapping, or no such version
     * @throws ProcessNotFoundException      no such process
     * @throws ProcessStateConflictException the plan has errors (the message lists them), or the process is not live
     */
    public void migrate(String processId, Integer targetVersion, java.util.Map<String, String> stepMapping,
                        java.util.Map<String, String> variableMapping, String reason, AuthorizationContext caller) {
        requireReason(reason);
        checkMigrationInput(stepMapping, variableMapping);
        var process = ref(processId);
        authorizeOperation(process.workflowDefinitionId(), caller, "migrate process '" + processId + "'");
        authorizeExistingTest(process, caller);
        refuseTestRunMigration(process, "migrate");
        var local = process.local();
        if (local != null && migrationPlanner != null) {
            var plan = migrationPlanner.plan(local, stepExecutionRepository.findByProcess(local), targetVersion,
                    stepMapping, variableMapping).plan();
            if (!plan.executable()) {
                throw new ProcessStateConflictException(processId, "migrate", process.status(),
                        String.join("; ", plan.errors().stream().map(p -> p.message()).toList()));
            }
        } else {
            var why = liveOrWhy(process.status());
            if (why != null) {
                throw new ProcessStateConflictException(processId, "migrate", process.status(), why);
            }
        }
        commandDispatcher.dispatch(new io.mateu.workflow.dtos.events.integration.MigrateProcessRequested(processId,
                targetVersion, stepMapping, variableMapping, reason.strip(), subject(caller)));
    }

    /** The versions a process of this definition can be migrated to, newest first. */
    public List<Integer> migrationVersions(String definitionId) {
        return migrationPlanner == null ? List.of() : migrationPlanner.versions(definitionId);
    }

    private static void checkMigrationInput(java.util.Map<String, String> stepMapping,
                                            java.util.Map<String, String> variableMapping) {
        io.mateu.workflow.application.services.UpstreamInputGuard.checkMapping(stepMapping, "a migration's step mapping");
        if (variableMapping != null) {
            variableMapping.keySet().forEach(name -> {
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("A variable to set needs a name.");
                }
            });
            InputLimits.checkNamedValues(List.copyOf(variableMapping.entrySet()), java.util.Map.Entry::getKey,
                    java.util.Map.Entry::getValue, "a migration's variables");
        }
    }

    /** A dry run's answer. */
    public record MigrationPreview(String processId, String workflowDefinitionId, int fromVersion, int toVersion,
                                   io.mateu.workflow.analysis.MigrationRules.Plan plan) {
    }

    /**
     * The gate of a manual operation: the definition's flow authorization (when it is on), and — whether
     * or not it is — {@code workflow.security.operations.required-roles}, every one of which the caller
     * must hold. Public so the incident retry applies the same one.
     */
    public void authorizeOperation(String definitionId, AuthorizationContext caller, String what) {
        authorize(definitionId, caller, what);
        if (operationsRequiredRoles.isEmpty()) {
            return;
        }
        var decision = FlowAuthorizationService.authorize(caller, List.of(), operationsRequiredRoles);
        if (!decision.allowed()) {
            log.warn("Refused to {}: caller '{}' is missing the operations role(s) {}", what,
                    caller == null ? null : caller.subject(), decision.missingRoles());
            throw FlowAuthorizationDeniedException.of(what, caller == null ? null : caller.subject(),
                    List.of(), decision.missingRoles());
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required for a manual operation.");
        }
        InputLimits.checkText(reason, "an operation's reason");
    }

    private static String liveOrWhy(ProcessStatus status) {
        return status != null && io.mateu.workflow.application.usecases.process.operate.ProcessOperationRules.LIVE.contains(status)
                ? null : "only a RUNNING, PAUSED or ERROR process is operated on by hand";
    }

    /** Who the operation is recorded as: the caller's subject, or null when nobody was identified. */
    private static String subject(AuthorizationContext caller) {
        return caller == null ? null : caller.subject();
    }

    private void request(String processId, String operation, Predicate<ProcessStatus> accepts,
                         Function<String, DomainEvent> command, AuthorizationContext caller) {
        var process = ref(processId);
        authorize(process.workflowDefinitionId(), caller, operation + " process '" + processId + "'");
        authorizeExistingTest(process, caller);
        if (!accepts.test(process.status())) {
            throw new ProcessStateConflictException(processId, operation, process.status(), null);
        }
        // Requested, not performed here: the process belongs to the node that owns it (its Kafka
        // partition, its shard), which is where the use case runs — under the process lock.
        commandDispatcher.dispatch(command.apply(processId));
    }

    /** What a command needs to know of a process: its definition, its status and, when this node holds it, the process. */
    private record ProcessRef(String id, String workflowDefinitionId, ProcessStatus status, Process local) {
    }

    /**
     * The process, from this node's store — or, in a sharded fleet with the read model on, from the
     * process index, which has seen every shard: a command for a process another shard owns is routed
     * there by the dispatcher, so it must not be refused here as unknown.
     */
    private ProcessRef ref(String processId) {
        var local = processRepository.findById(processId);
        if (local.isPresent()) {
            return new ProcessRef(processId, local.get().getWorkflowDefinitionId(), local.get().getStatus(), local.get());
        }
        if (readModel.isEnabled()) {
            var index = processIndex.getIfAvailable();
            var row = index == null ? null : index.findByProcessId(processId).orElse(null);
            if (row != null) {
                return new ProcessRef(processId, row.workflowDefinitionId(), ProcessStatus.valueOf(row.status()), null);
            }
        }
        throw new ProcessNotFoundException("No process with id '" + processId + "'.");
    }

    /**
     * The definition's flow-authorization gate, applied to an existing process of it. Off unless
     * {@code workflow.security.flow-authorization.enabled}; a definition that is gone requires nothing.
     */
    private void authorize(String definitionId, AuthorizationContext caller, String what) {
        if (!flowAuthorizationEnabled || definitionId == null) {
            return;
        }
        var definition = workflowDefinitionRepository.findById(definitionId).orElse(null);
        if (definition == null) {
            return;
        }
        var decision = FlowAuthorizationService.authorize(caller, definition.requiredScopes(), definition.requiredRoles());
        if (!decision.allowed()) {
            log.warn("Refused to {} (workflow '{}'): caller '{}' is missing scopes {} and roles {}", what,
                    definitionId, caller == null ? null : caller.subject(),
                    decision.missingScopes(), decision.missingRoles());
            throw FlowAuthorizationDeniedException.of(what, caller == null ? null : caller.subject(),
                    decision.missingScopes(), decision.missingRoles());
        }
    }

    // ── Refusals ─────────────────────────────────────────────────────────────────────────────────

    /** No such process (or step execution of it). */
    public static class ProcessNotFoundException extends RuntimeException {
        public ProcessNotFoundException(String message) {
            super(message);
        }
    }

    /** The process is in a status the operation is not applied in. */
    public static class ProcessStateConflictException extends RuntimeException {
        private final ProcessStatus status;

        public ProcessStateConflictException(String processId, String operation, ProcessStatus status, String why) {
            super("Cannot " + operation + " process '" + processId + "' while it is " + status
                    + (why == null ? "." : ": " + why + "."));
            this.status = status;
        }

        public ProcessStatus status() {
            return status;
        }
    }

    /** The business key already names a process of another definition. */
    public static class BusinessKeyTakenException extends RuntimeException {
        public BusinessKeyTakenException(String businessKey, String definitionId) {
            super("Business key '" + businessKey + "' is already taken by a process of workflow '"
                    + definitionId + "'.");
        }
    }

    /** The definition takes no new instances (disabled, archived). */
    public static class NotAcceptingInstancesException extends RuntimeException {
        public NotAcceptingInstancesException(String definitionId, String status) {
            super("Workflow '" + definitionId + "' is " + status + " and does not accept new processes.");
        }
    }
}
