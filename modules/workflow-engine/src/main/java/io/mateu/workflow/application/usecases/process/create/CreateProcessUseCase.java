package io.mateu.workflow.application.usecases.process.create;

import io.mateu.workflow.time.EngineClock;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.UnknownWorkflowDefinitionException;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import io.mateu.workflow.security.FlowAuthorizationService;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.BusinessKeyPolicy;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.services.StepTimeoutDefaults;
import io.mateu.workflow.application.usecases.process.parentnotify.ChildProcesses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class CreateProcessUseCase {

    final ProcessRepository processRepository;
    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final StepExecutionRepository stepExecutionRepository;
    final WorkflowMetrics workflowMetrics;
    // ObjectProvider, like NotifyParentStepService: the step-update pipeline reaches process
    // creation, so a direct dependency would close an injection cycle.
    final ObjectProvider<UpdateStepExecutionUseCase> updateStepExecutionUseCase;
    final ObjectProvider<io.mateu.workflow.application.services.ProcessTrace> processTrace;
    // A provider: cancelling a holder (businessKeyPolicy terminateRunning) reaches back into the step pipeline.
    final ObjectProvider<io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase> cancelProcessUseCase;

    /**
     * Whether a process created with a caller's W3C trace context joins that trace. On by default:
     * it changes nothing for a caller that sends no context, and a caller that sends one is asking
     * for exactly this. Off, every process keeps the trace derived from its id, as before.
     */
    @org.springframework.beans.factory.annotation.Value("${workflow.tracing.join-caller-trace:true}")
    boolean joinCallerTrace = true;

    /**
     * Fallback timeout, in milliseconds, for ACTION and RULE steps that declare none. Zero — the
     * default — leaves them with no deadline, which means a lost dispatch or a lost worker reply
     * stops that process permanently and invisibly. See {@link StepTimeoutDefaults}.
     */
    @org.springframework.beans.factory.annotation.Value("${workflow.default-step-timeout-ms:0}")
    long defaultStepTimeoutMillis;

    /**
     * How deeply PROCESS steps may nest — a parent's child's child, and so on. Reaching it refuses
     * the next child and fails the PROCESS step that asked for it.
     *
     * <p>This is the only thing standing between the engine and a recursive definition.
     * {@code checkInvariants} refuses a workflow that names <em>itself</em> as its child, which
     * catches the obvious spelling and none of the others: A starting B starting A validates
     * cleanly, because neither definition can see the other, and each generation starts the next
     * for as long as the database will take rows. A depth limit needs no cross-definition
     * knowledge, and it also catches the case a static check never could — a chain assembled at
     * runtime through dynamically injected steps.
     *
     * <p>Twenty is far past any modelled hierarchy and far short of trouble.
     */
    @org.springframework.beans.factory.annotation.Value("${workflow.max-process-depth:20}")
    int maxProcessDepth;

    /**
     * Whether a definition's {@code requiredScopes}/{@code requiredRoles} are enforced.
     *
     * <p>Off by default, and it has to be: the requirements are declarative and a deployment that has
     * never configured an identity would have every restricted definition refused the moment it
     * upgraded. On, the rule is fail-closed — a caller nobody could identify holds nothing, so a
     * definition that requires anything refuses them.
     */
    @org.springframework.beans.factory.annotation.Value("${workflow.security.flow-authorization.enabled:false}")
    boolean flowAuthorizationEnabled;

    public void handle(CreateProcessCommand command) {
        if (isAPROCESSStepsChild(command) && command.continuedFromProcessId() == null) {
            var child = childToStart(command);
            if (child != null) {
                create(child);
            }
            return;
        }
        create(command);
    }

    private void create(CreateProcessCommand command) {
        // Idempotency: a redelivered creation event carries the same processId and/or
        // businessKey — skip silently instead of creating a duplicate process.
        if (command.processId() != null && !command.processId().isBlank()) {
            if (processRepository.findById(command.processId()).isPresent()) {
                log.warn("Process with process Id '{}' already exists, ignoring duplicate creation request",
                        command.processId());
                return;
            }
        }
        // A key held by a run that may hand it over (businessKeyPolicy, CONTINUE_AS_NEW) is released here, before
        // the new run is created: the key stays UNIQUE in the store, held by one run at a time.
        if (command.businessKey() != null && !command.businessKey().isBlank()) {
            var holder = processRepository.findByBusinessKey(command.businessKey()).orElse(null);
            if (holder != null) {
                var reusable = reusableKeyHolder(command, holder);
                if (reusable == null) {
                    log.warn("Process with business key '{}' already exists, ignoring duplicate creation request",
                            command.businessKey());
                    return;
                }
                processRepository.releaseBusinessKey(reusable);
            }
        }

        // A child of a child of a child…: refuse before creating anything, and fail the PROCESS
        // step that asked, so a recursive definition surfaces as one failed step instead of as an
        // unbounded fan-out. See maxProcessDepth.
        if (command.parentStepExecutionId() != null && !command.parentStepExecutionId().isBlank()
                && ancestorCount(command.parentStepExecutionId()) >= maxProcessDepth) {
            log.error("Refusing to create a child process of workflow definition '{}' for business key"
                            + " '{}': it would nest more than {} levels deep, which a recursive"
                            + " definition is the usual cause of",
                    command.workflowDefinitionId(), command.businessKey(), maxProcessDepth);
            updateStepExecutionUseCase.getObject().handle(new UpdateStepExecutionCommand(
                    command.parentStepExecutionId(),
                    List.of(),
                    "Child process not started: workflow '" + command.workflowDefinitionId()
                            + "' would nest more than " + maxProcessDepth + " levels deep."
                            + " Check for a cycle between PROCESS steps.",
                    io.mateu.workflow.domain.aggregates.StepExecutionStatus.ERROR));
            return;
        }

        // The fallback timeout is applied here, at the one moment a definition becomes a
        // process, and nowhere else. Both copies this method freezes — the step's stepJson and
        // the process's definition snapshot — then carry it, while the stored definition the UI
        // reads and writes back stays exactly as its author wrote it. Applying it in the
        // repository instead would let an editor round-trip bake the default in permanently.
        var workflowDefinition = StepTimeoutDefaults.applyTo(
                command.definition() != null ? command.definition()
                        : workflowDefinitionRepository.findById(command.workflowDefinitionId())
                        .orElseThrow(() -> new UnknownWorkflowDefinitionException(command.workflowDefinitionId())),
                defaultStepTimeoutMillis);

        // A disabled workflow accepts no new instances — which the cron scheduler already
        // honoured and this path did not, so anything creating a process directly (the UI, an
        // upstream event, MCP) walked straight past it. Either source can say no: an operator
        // taking it out of service, or the definition itself declaring that it is not to run.
        if (!workflowDefinition.status().accceptsNewInstances()) {
            log.warn("Workflow definition '{}' is {} — process creation for business key '{}' ignored",
                    workflowDefinition.id(), workflowDefinition.status(), command.businessKey());
            return;
        }

        // Who may start this. Checked here rather than at each door because there are four of them —
        // the UI, an upstream record, an MCP call, the cron scheduler — and a rule enforced in four
        // places is a rule that holds in three of them after the fifth door is added.
        refuseIfCallerMayNotStart(workflowDefinition, command);
        AtomicInteger position = new AtomicInteger(1);
        var stepExecutions = workflowDefinition.steps().stream()
                .map(step -> StepExecution.create(step, command.processId(), position.getAndIncrement())).toList();

        stepExecutions.forEach(stepExecutionRepository::save);

        var process = Process
                .create(
                        command.processId(),
                        workflowDefinition,
                        command.businessKey(),
                        command.variables() != null?command.variables(): List.of(),
                        command.parentStepExecutionId()
                );
        if (workflowDefinition.paused()) {
            // Born paused: creation is deliberately still accepted while the definition is
            // paused (cron included), but nothing may start — the orchestration gate holds
            // everything until the definition (or the process) is resumed. The @With copies
            // start with an empty event list, so carry the ProcessCreated event over.
            var events = process.popEvents();
            process = process.withStatus(ProcessStatus.PAUSED).withPausedAt(EngineClock.now());
            events.forEach(process::send);
            log.info("Workflow definition '{}' is paused — process {} created PAUSED",
                    workflowDefinition.id(), process.getId());
        }
        // After any @With copy above, which would not carry it.
        if (command.continuedFromProcessId() != null) {
            process.continues(command.continuedFromProcessId());
        }
        processRepository.save(process);

        // Joined before anything reads the process's anchor: the step-over this creation triggers
        // must continue the caller's trace, not the derived one.
        if (joinCallerTrace && command.traceContext() != null && processTrace != null) {
            var trace = processTrace.getIfAvailable();
            if (trace != null) {
                trace.join(process.getId(), command.traceContext());
            }
        }

        workflowMetrics.processStarted(command.workflowDefinitionId());

        // enviar evento proceso creado (para step over)


    }

    /**
     * Whether {@code holder}'s business key may be reused by this creation, and so which run the new one
     * takes it over from (null: it may not — the creation collapses into the holder, as it always did).
     *
     * <ul>
     *   <li>A CONTINUE_AS_NEW continues its own run: always reused.</li>
     *   <li>A key held by another definition's process is never reused.</li>
     *   <li>Otherwise the definition's {@code businessKeyPolicy} decides (see {@link BusinessKeyPolicy});
     *       {@code terminateRunning} cancels a running holder first, here, before the new run is created.</li>
     * </ul>
     */
    private String reusableKeyHolder(CreateProcessCommand command, Process holder) {
        if (command.continuedFromProcessId() != null) {
            return holder.getId().equals(command.continuedFromProcessId()) ? holder.getId() : null;
        }
        if (!java.util.Objects.equals(holder.getWorkflowDefinitionId(), command.workflowDefinitionId())) {
            return null;
        }
        var definition = command.definition() != null ? command.definition()
                : workflowDefinitionRepository.findById(command.workflowDefinitionId()).orElse(null);
        if (definition == null) {
            return null;
        }
        var policy = definition.keyPolicy();
        switch (policy.decide(holder.getStatus())) {
            case KEEP:
                return null;
            case TERMINATE_AND_REUSE:
                log.info("Business key '{}' of workflow '{}' is held by running process {}: cancelled for a new run"
                        + " (businessKeyPolicy terminateRunning)", command.businessKey(), definition.id(), holder.getId());
                cancelProcessUseCase.getObject().handle(
                        new io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand(holder.getId()));
                workflowMetrics.businessKeyReused(definition.id(), policy.text());
                return holder.getId();
            default:
                log.info("Business key '{}' of workflow '{}' passes from {} process {} to a new run"
                                + " (businessKeyPolicy {})", command.businessKey(), definition.id(), holder.getStatus(),
                        holder.getId(), policy.text());
                workflowMetrics.businessKeyReused(definition.id(), policy.text());
                return holder.getId();
        }
    }

    /**
     * How many processes stand between this one and a top-level start, walking parent step
     * execution to owning process and up again. Bounded by the limit itself: the answer is only
     * ever compared against it, so there is no reason to walk a chain further than that — and a
     * parent chain that somehow looped would otherwise walk for ever.
     */
    /**
     * The definition's flow-authorization gate.
     *
     * <p><b>Only caller-originated creations are judged.</b> A child spawned by a PROCESS step and a
     * run started by cron are the engine acting on its own behalf, not somebody asking for something:
     * re-judging them would mean a scheduled definition could never run (the scheduler holds no
     * scopes) and a modelled child could never be spawned, which is not authorization, it is
     * breakage. What governs those is who was allowed to author the definition in the first place.
     *
     * @throws FlowAuthorizationDeniedException if the caller is missing anything the definition requires
     */
    private void refuseIfCallerMayNotStart(
            io.mateu.workflow.domain.aggregates.WorkflowDefinition definition, CreateProcessCommand command) {
        if (!flowAuthorizationEnabled) {
            return;
        }
        var engineOriginated = (command.parentStepExecutionId() != null && !command.parentStepExecutionId().isBlank())
                // By identity: a resolved caller whose subject is "system" is not the engine.
                || AuthorizationContext.isSystem(command.caller());
        if (engineOriginated) {
            return;
        }
        refuseIfCallerMayNotStart(definition, command.caller(), command.businessKey());
    }

    /**
     * The check itself, shared with every door that sets up a start the engine will later make on its
     * own behalf — a schedule, an event trigger, a schedule's run-now: whoever creates or edits one must
     * be allowed to start its workflow, or SYSTEM would start it for them.
     *
     * @param context what is being judged, for the log (a business key, a schedule id…)
     * @throws FlowAuthorizationDeniedException if the caller is missing anything the definition requires
     */
    /** A creation a PROCESS step asked for, under the deterministic key of its first child. */
    private static boolean isAPROCESSStepsChild(CreateProcessCommand command) {
        return command.parentStepExecutionId() != null && !command.parentStepExecutionId().isBlank()
                && ChildProcesses.firstKey(command.parentStepExecutionId()).equals(command.businessKey());
    }

    /**
     * Which child, if any, a PROCESS step's creation request should start — null for none.
     *
     * <p>Plain business-key dedupe is not enough here. A PROCESS step that runs its child again (it
     * retries after the child failed, an operator retries it, the process restarts) asks under the
     * same key as the first time, and dedupe matched it to the child that already finished and
     * dropped it — leaving the parent step PENDING, and the parent RUNNING, forever. So:
     * <ul>
     *   <li>no child yet: start the first one, under {@code parent:<id>};</li>
     *   <li>a child still in flight: this is a redelivery, or a retry after a timeout, which
     *       re-attaches to that child — start nothing;</li>
     *   <li>every child finished and the parent step is waiting again: run the child afresh, under
     *       the next key ({@code parent:<id>#2}, …). A parent step that is not waiting (it already
     *       took the child's outcome) makes this a stale redelivery — start nothing.</li>
     * </ul>
     */
    private CreateProcessCommand childToStart(CreateProcessCommand command) {
        var parentStepExecutionId = command.parentStepExecutionId();
        var children = ChildProcesses.of(processRepository, parentStepExecutionId);
        if (children.isEmpty()) {
            return command;
        }
        var live = children.stream().filter(child -> !ChildProcesses.isFinished(child)).findFirst();
        if (live.isPresent()) {
            log.info("PROCESS step execution {} already has child process {} in flight — not starting another",
                    parentStepExecutionId, live.get().getId());
            return null;
        }
        var waiting = stepExecutionRepository.findById(parentStepExecutionId)
                .map(parent -> io.mateu.workflow.domain.aggregates.StepExecutionStatus.PENDING.equals(parent.getStatus()))
                .orElse(false);
        if (!waiting) {
            log.warn("Process with business key '{}' already exists, ignoring duplicate creation request",
                    command.businessKey());
            return null;
        }
        var key = ChildProcesses.key(parentStepExecutionId, children.size() + 1);
        log.info("PROCESS step execution {} runs its child again as '{}' (the previous one ended {})",
                parentStepExecutionId, key, children.get(children.size() - 1).getStatus());
        return new CreateProcessCommand(command.processId(), command.workflowDefinitionId(), key,
                command.variables(), parentStepExecutionId, command.caller(), command.traceContext());
    }

    public static void refuseIfCallerMayNotStart(io.mateu.workflow.domain.aggregates.WorkflowDefinition definition,
                                                 AuthorizationContext caller, String context) {
        var decision = FlowAuthorizationService.authorize(
                caller, definition.requiredScopes(), definition.requiredRoles());
        if (!decision.allowed()) {
            log.warn("Refused to start workflow definition '{}' ({}): caller '{}' is"
                            + " missing scopes {} and roles {}",
                    definition.id(), context,
                    caller == null ? null : caller.subject(),
                    decision.missingScopes(), decision.missingRoles());
            throw FlowAuthorizationDeniedException.of(
                    "start workflow '" + definition.id() + "'",
                    caller == null ? null : caller.subject(),
                    decision.missingScopes(), decision.missingRoles());
        }
    }

    private int ancestorCount(String parentStepExecutionId) {
        int depth = 0;
        var stepExecutionId = parentStepExecutionId;
        while (stepExecutionId != null && depth <= maxProcessDepth) {
            depth++;
            var parentStep = stepExecutionRepository.findById(stepExecutionId).orElse(null);
            if (parentStep == null) {
                break;
            }
            var parentProcess = processRepository.findById(parentStep.getProcessId()).orElse(null);
            if (parentProcess == null) {
                break;
            }
            stepExecutionId = parentProcess.getParentStepExecutionId();
        }
        return depth;
    }

}
