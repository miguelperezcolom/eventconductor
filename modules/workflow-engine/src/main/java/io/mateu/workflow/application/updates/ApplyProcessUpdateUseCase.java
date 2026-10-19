package io.mateu.workflow.application.updates;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.ProcessUpdateRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.MessageSubscriptionService;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.UpdateDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.services.UpdateEvaluator;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.events.integration.ProcessUpdateRequested;
import io.mateu.workflow.time.EngineClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Applies an update to a process — on the node that owns it, under its lock, in one transaction.
 *
 * <p>Re-reads the update's row first: one that is no longer {@code REQUESTED} was already applied (a
 * redelivered command, a retry that re-dispatched it), so nothing happens twice. Then, against the live
 * process: is it accepting ({@code RUNNING}/{@code PENDING}, the {@code acceptedWhile}/{@code completes}
 * step waiting)? do the guards hold? what does {@code set} write and what does {@code reply} answer
 * ({@link UpdateEvaluator})? Only an accepted update writes anything — the variables, the resumed step,
 * the result — and all of it with the process, so the outcome and the change it describes commit together.
 *
 * <p>What follows a change of variables follows it, as for an operator's change
 * ({@code SetProcessVariablesUseCase}): message correlation keys and timers re-arm, and a RUNNING process
 * steps over so a step held by a link guard is released. A resumed {@code WAIT_FOR_MESSAGE} moves the flow
 * on through its COMPLETED status change, exactly as a correlated message does.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApplyProcessUpdateUseCase {

    /** The processes an update is applied to: the live ones whose flow can still take it. */
    public static final Set<ProcessStatus> ACCEPTING = Set.of(ProcessStatus.RUNNING, ProcessStatus.PENDING);

    final ProcessUpdateRepository updateRepository;
    final ProcessRepository processRepository;
    final StepExecutionRepository stepExecutionRepository;
    final LogMessageRepository logMessageRepository;
    final ProcessLockService processLockService;
    final MessageSubscriptionService messageSubscriptionService;
    final StepOverProcessUseCase stepOverProcessUseCase;
    final WorkflowMetrics workflowMetrics;
    final ProcessUpdateWaiters waiters;

    @Value("${workflow.sync.max-reply-bytes:262144}")
    long maxResultBytes = 262_144;

    @Value("${workflow.sync.retention:PT24H}")
    Duration retention = Duration.ofHours(24);

    /** Opens a payload whose protected fields came sealed, seals a sensitive result, keys the fingerprint. */
    io.mateu.workflow.crypto.VariableEncryption encryption = io.mateu.workflow.crypto.VariableEncryption.DISABLED;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void encryption(io.mateu.workflow.crypto.VariableEncryption encryption) {
        this.encryption = encryption == null ? io.mateu.workflow.crypto.VariableEncryption.DISABLED : encryption;
    }

    public void handle(ProcessUpdateRequested command) {
        ProcessLockService.runExclusivelyOrThrow(processLockService, command.processId(), () -> doHandle(command));
    }

    private void doHandle(ProcessUpdateRequested command) {
        var update = updateRepository.findById(command.updateId()).orElse(null);
        if (update == null) {
            update = recordArrival(command);
            if (update == null) {
                return;
            }
        }
        if (update.isFinal() || !command.processId().equals(update.processId())) {
            log.info("Update {} of process {} is already {} — nothing to do", update.id(), update.processId(), update.status());
            return;
        }
        var process = processRepository.findById(command.processId()).orElse(null);
        if (process == null) {
            finish(update, null, ProcessUpdate.Status.NOT_ACCEPTED, null, "The process no longer exists.");
            return;
        }
        var definition = definitionOf(process);
        var declared = definition == null ? Optional.<UpdateDefinition>empty() : definition.update(update.name());
        if (declared.isEmpty()) {
            finish(update, process, ProcessUpdate.Status.NOT_ACCEPTED, null, "The definition version the process runs ("
                    + process.getWorkflowDefinitionId() + " v" + process.getWorkflowDefinitionVersion()
                    + ") declares no update '" + update.name() + "'.");
            return;
        }
        var handler = declared.get();
        var steps = stepExecutionRepository.findByProcessId(process.getId());
        var why = whyNotAccepting(process, handler, steps);
        if (why != null) {
            finish(update, process, ProcessUpdate.Status.NOT_ACCEPTED, null, why);
            return;
        }
        Object payload;
        try {
            payload = UpdateEvaluator.payloadOf(UpdateEvaluator.open(command.payload(), encryption));
        } catch (RuntimeException e) {
            finish(update, process, ProcessUpdate.Status.FAILED, null, e.getMessage());
            return;
        }
        var sensitive = io.mateu.workflow.application.services.SensitiveVariableMasking.namesOf(process);
        var outcome = UpdateEvaluator.evaluate(handler, process, payload, maxResultBytes,
                sensitive.isEmpty() ? null : sensitive);
        switch (outcome) {
            case UpdateEvaluator.Refused refused ->
                    finish(update, process, ProcessUpdate.Status.REFUSED, null, refused.message());
            case UpdateEvaluator.Failed failed -> {
                log.warn("Update '{}' of process {} failed: {}", update.name(), process.getId(), failed.message());
                finish(update, process, ProcessUpdate.Status.FAILED, null, failed.message());
            }
            case UpdateEvaluator.Applied applied -> apply(update, process, handler, steps, applied,
                    !sensitive.isEmpty() && handler.allowsSensitiveVariables());
        }
    }

    private void apply(ProcessUpdate update, Process process, UpdateDefinition handler, List<StepExecution> steps,
                       UpdateEvaluator.Applied applied, boolean sensitiveResult) {
        if (!applied.variables().isEmpty()) {
            process.updateVariables(applied.variables());
            processRepository.save(process);
        }
        var resumed = handler.completes() == null ? null : waitingExecution(steps, handler.completes());
        if (resumed != null) {
            resumed.updateStatus(StepExecutionStatus.COMPLETED);
            stepExecutionRepository.save(resumed);
            if (resumed.getStartedAt() != null) {
                workflowMetrics.stepExecutionFinished(resumed.getWorkflowDefinitionId(), StepExecutionStatus.COMPLETED,
                        Duration.between(resumed.getStartedAt(), EngineClock.now()));
            }
        }
        finish(update, process, ProcessUpdate.Status.COMPLETED,
                sensitiveResult ? sealedResult(applied.result(), encryption) : applied.result(), null);
        log(process, resumed == null ? null : resumed.id(), "Update '" + update.name() + "' applied"
                + by(update)
                + (applied.variables().isEmpty() ? "" : ": set " + String.join(", ",
                        applied.variables().stream().map(io.mateu.workflow.domain.aggregates.Variable::name).toList()))
                + (resumed == null ? "" : "; resumed step '" + handler.completes() + "'"));
        if (!applied.variables().isEmpty()) {
            // What derives from the variables follows them, as when a worker or an operator writes them.
            messageSubscriptionService.rearm(process);
        }
        if (resumed == null && !applied.variables().isEmpty() && ProcessStatus.RUNNING.equals(process.getStatus())) {
            // A step held by a link guard reading one of these may be free to go now. (A resumed step moves
            // the flow on through its own COMPLETED status change.)
            stepOverProcessUseCase.handle(new StepOverProcessCommand(process.getId()));
        }
    }

    /**
     * The result of an update allowed to read sensitive variables, as it is recorded: sealed whole (a JSON
     * string holding the envelope), opened only for the caller (see {@code ProcessUpdateService#forCaller}).
     * Without encryption it is kept as it is — memory mode holds plaintext in the heap anyway — and every
     * door still masks it.
     */
    static String sealedResult(String result, io.mateu.workflow.crypto.VariableEncryption encryption) {
        if (result == null || encryption == null || !encryption.enabled()) {
            return result;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(encryption.seal(result));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Why the process does not take this update now, or null when it does. */
    static String whyNotAccepting(Process process, UpdateDefinition handler, List<StepExecution> steps) {
        if (!ACCEPTING.contains(process.getStatus())) {
            return "The process is " + process.getStatus() + "; updates are accepted while it is RUNNING or PENDING.";
        }
        if (handler.completes() != null && waitingExecution(steps, handler.completes()) == null) {
            return "Update '" + handler.name() + "' resumes step '" + handler.completes()
                    + "', which is not waiting now.";
        }
        if (handler.acceptedWhile() != null
                && handler.acceptedWhile().stream().noneMatch(stepId -> isWaiting(steps, stepId))) {
            return "Update '" + handler.name() + "' is accepted only while " + String.join(" or ", handler.acceptedWhile())
                    + (handler.acceptedWhile().size() == 1 ? " is" : " are") + " waiting, and "
                    + (handler.acceptedWhile().size() == 1 ? "it is" : "none is") + " now.";
        }
        return null;
    }

    /** The execution of {@code stepId} waiting for a message (PENDING and started), or null. */
    static StepExecution waitingExecution(List<StepExecution> steps, String stepId) {
        return steps.stream()
                .filter(step -> stepId.equals(step.getStepId()))
                .filter(step -> StepExecutionStatus.PENDING.equals(step.getStatus()) && step.getStartedAt() != null)
                .findFirst().orElse(null);
    }

    /** Whether an execution of {@code stepId} is in progress — waiting for a message, a worker, a person or a timer. */
    static boolean isWaiting(List<StepExecution> steps, String stepId) {
        return steps.stream().anyMatch(step -> stepId.equals(step.getStepId())
                && (StepExecutionStatus.PENDING.equals(step.getStatus()) || StepExecutionStatus.RUNNING.equals(step.getStatus())
                || StepExecutionStatus.AWAITING_RETRY.equals(step.getStatus())));
    }

    /**
     * A command produced straight to the {@code upstream} topic, with no row from a door: recorded here,
     * its payload validated against the update's schema first — the checks the door would have made.
     */
    private ProcessUpdate recordArrival(ProcessUpdateRequested command) {
        var process = processRepository.findById(command.processId()).orElse(null);
        if (process == null) {
            log.warn("Update {} for unknown process {} — ignored", command.updateId(), command.processId());
            return null;
        }
        var definition = definitionOf(process);
        var declared = definition == null ? null : definition.update(command.updateName()).orElse(null);
        var now = EngineClock.now();
        var sensitive = io.mateu.workflow.application.services.SensitiveVariableMasking.namesOf(process);
        String plain;
        try {
            plain = UpdateEvaluator.open(command.payload(), encryption);
        } catch (RuntimeException e) {
            plain = command.payload();
        }
        var update = new ProcessUpdate(command.updateId(), process.getId(), process.getWorkflowDefinitionId(),
                command.updateName(), command.updateId(),
                encryption.fingerprint(ProcessUpdateService.canonicalOf(command.updateName(), plain)),
                ProcessUpdateService.recorded(declared, plain, sensitive, encryption), ProcessUpdate.Status.REQUESTED, null, null,
                command.requestedBy(), false, now, null, now.plus(retention));
        if (declared != null) {
            var problems = ProcessUpdateService.payloadProblems(declared, plain);
            if (!problems.isEmpty()) {
                update = update.finished(ProcessUpdate.Status.FAILED, null,
                        "The payload is not valid: " + String.join("; ", problems), now);
            }
        }
        try {
            updateRepository.insert(update);
        } catch (ProcessUpdateRepository.DuplicateUpdateException e) {
            return updateRepository.findById(command.updateId()).orElse(null);
        }
        return update;
    }

    private void finish(ProcessUpdate update, Process process, ProcessUpdate.Status status, String result, String error) {
        var finished = update.finished(status, result, error, EngineClock.now());
        updateRepository.save(finished);
        if (status != ProcessUpdate.Status.COMPLETED && process != null) {
            log(process, null, "Update '" + update.name() + "' " + status.name().toLowerCase().replace('_', ' ')
                    + by(update) + (error == null ? "" : ": " + error));
        }
        workflowMetrics.processUpdateFinished(update.workflowDefinitionId(), update.name(), status.name());
        waiters.updateFinished(update.id());
    }

    private void log(Process process, String stepExecutionId, String message) {
        logMessageRepository.save(new LogMessage(UUID.randomUUID().toString(), EngineClock.now(), process.getId(),
                stepExecutionId, MessageType.Info.name(), message, "update"));
    }

    private static String by(ProcessUpdate update) {
        return update.requestedBy() == null ? "" : " (by " + update.requestedBy() + ")";
    }

    /** The definition the process runs — its snapshot, never the latest version. */
    static WorkflowDefinition definitionOf(Process process) {
        var json = process.getWorkflowDefinitionJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return pojoFromJson(json, WorkflowDefinition.class);
        } catch (Exception e) {
            log.warn("The definition snapshot of process {} cannot be read: {}", process.getId(), e.getMessage());
            return null;
        }
    }
}
