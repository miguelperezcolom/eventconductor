package io.mateu.workflow.application.usecases.process.operate;

import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.services.MessageSubscriptionService;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase;
import io.mateu.workflow.application.usecases.process.parentnotify.ChildProcesses;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Completes one step execution by hand, as if its worker had answered COMPLETED with
 * {@code outputVariables}, and lets the flow carry on past it.
 *
 * <p>For the step that cannot succeed on its own but whose effect the operator has brought about
 * another way — the payment was taken by phone, the partner confirmed by email — or that is stuck at a
 * worker that will never answer. Accepted from PENDING, RUNNING, AWAITING_RETRY, ERROR and TIMEOUT
 * (see {@link ProcessOperationRules#whyNotCompleteStep}); a step that is already COMPLETED is left
 * alone, so a redelivered request is harmless.
 *
 * <h2>What it does</h2>
 *
 * <ul>
 *   <li>A step still at a worker is told to stop ({@link TaskCancellationRequested}), as a cancel
 *       does. Its answer, if it comes anyway, is dropped: the step is terminal by then. A PROCESS
 *       step's still-running child process is cancelled.</li>
 *   <li>The output variables are written onto the process, exactly as a worker's reply would be — and
 *       what derives from them (message correlation keys, timers) follows them.</li>
 *   <li>The step goes to COMPLETED through the ordinary status pipeline: the process status is
 *       recomputed and its successors start. A failed process whose last blocking failure this was
 *       goes back to RUNNING ({@code finished} cleared, so the analytics read model retracts its
 *       failure); one with another failure still standing stays ERROR. A PAUSED process holds the
 *       successors until it is resumed.</li>
 * </ul>
 *
 * <p>A step completed by hand is completed: if the saga later fails, its compensation runs like any
 * other completed step's. That is the point of saying it completed.
 *
 * <h2>Loops and forEach</h2>
 *
 * <ul>
 *   <li>A loop step's <b>current pass</b> completes like any step, and its {@code repeat}/{@code loopBack}
 *       condition is then decided as if its worker had answered. An earlier, superseded pass is history
 *       and is refused.</li>
 *   <li>A forEach <b>iteration</b> is completed while its container is still running it: the outputs are
 *       recorded on the iteration — as an iteration's worker report is, never on the process — and the
 *       container collects them into its {@code output} when it completes.</li>
 *   <li>A forEach <b>container</b> completed by hand stops its iterations (those in flight are told to)
 *       and completes the step; its outputs are written onto the process like any step's. Its
 *       {@code output} collection is not produced — give it as an output variable if what follows
 *       needs it. Iterations that had completed stay completed, and are compensated if the saga fails.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompleteStepManuallyUseCase {

    final ProcessRepository processRepository;
    final StepExecutionRepository stepExecutionRepository;
    final ProcessLockService processLockService;
    final DownstreamEventPublisher downstreamEventPublisher;
    final MessageSubscriptionService messageSubscriptionService;
    final StepOverProcessUseCase stepOverProcessUseCase;
    final ProcessOperationRules rules;
    final OperatorAudit audit;
    // A provider: cancelling a child reaches back into the step pipeline, as CancelChildProcessService does.
    final ObjectProvider<CancelProcessUseCase> cancelProcessUseCase;

    public void handle(CompleteStepManuallyCommand command) {
        var completedProcessStep = new AtomicReference<StepExecution>();
        ProcessLockService.runExclusivelyOrThrow(processLockService, command.processId(),
                () -> completedProcessStep.set(doHandle(command)));
        // The child runs under its own lock, taken after this one is released — the same order the
        // cancel cascade keeps, so a parent and a child never hold one lock each waiting for the other.
        var step = completedProcessStep.get();
        if (step != null) {
            ChildProcesses.of(processRepository, step.id()).stream()
                    .filter(child -> !ChildProcesses.isFinished(child))
                    .forEach(child -> {
                        log.info("PROCESS step {} completed by hand — cancelling its child process {}",
                                step.id(), child.getId());
                        cancelProcessUseCase.getObject().handle(new CancelProcessCommand(child.getId()));
                    });
        }
    }

    /** A container completed by hand: its iterations still to run, or running, will not. */
    private void stopIterations(StepExecution container, List<StepExecution> steps) {
        for (var iteration : io.mateu.workflow.domain.services.StepExecutions.iterationsOf(container, steps)) {
            if (iteration.getStatus().isTerminal()) {
                continue;
            }
            if (iteration.getStatus().isInFlightAtAWorker()) {
                downstreamEventPublisher.publish(new TaskCancellationRequested(iteration.getId()), iteration.topic());
            }
            iteration.cancelQuietly();
            stepExecutionRepository.save(iteration);
        }
    }

    /** @return the step, when it is a PROCESS step whose children must now be cancelled; null otherwise */
    private StepExecution doHandle(CompleteStepManuallyCommand command) {
        var process = processRepository.findById(command.processId()).orElse(null);
        if (process == null) {
            log.warn("Complete step: no process {} — ignoring", command.processId());
            return null;
        }
        var target = stepExecutionRepository.findById(command.stepExecutionId())
                .filter(found -> process.getId().equals(found.getProcessId()))
                .orElse(null);
        if (target == null) {
            audit.refused(process.getId(), null, OperatorAudit.COMPLETE_STEP, command.requestedBy(), command.reason(),
                    "the process has no step execution " + command.stepExecutionId());
            return null;
        }
        if (StepExecutionStatus.COMPLETED.equals(target.getStatus())) {
            // Already done — by an earlier delivery of this request, or by its worker in the meantime.
            log.info("Complete step: step execution {} of process {} is already COMPLETED", target.id(), process.getId());
            return null;
        }
        var steps = stepExecutionRepository.findByProcess(process);
        var why = rules.whyNotCompleteStep(process, target, steps);
        if (why != null) {
            audit.refused(process.getId(), target.id(), OperatorAudit.COMPLETE_STEP, command.requestedBy(),
                    command.reason(), "step " + target.getStepId() + " of a " + process.getStatus() + " process: " + why);
            return null;
        }

        var wasStatus = target.getStatus();
        if (wasStatus.isInFlightAtAWorker()) {
            downstreamEventPublisher.publish(new TaskCancellationRequested(target.getId()), target.topic());
        }

        // Reopen a failed process before its step is completed — the status pipeline the completion
        // drives would otherwise keep ERROR (it is sticky there) and hold the successors.
        var otherFailures = io.mateu.workflow.domain.services.StepExecutions.current(steps).stream()
                .filter(se -> !se.getId().equals(target.getId()))
                .anyMatch(ProcessOperationRules::blocksTheFlow);
        var updated = process;
        var reopens = ProcessStatus.ERROR.equals(process.getStatus()) && !otherFailures && !target.isIteration();
        if (reopens) {
            updated = process.withStatus(ProcessStatus.RUNNING).withFinished(null);
        }
        var outputs = command.outputVariables() == null ? List.<Variable>of() : command.outputVariables();
        if (target.isIteration()) {
            // An iteration's report stays on the iteration; its container collects it.
            target.recordOutput(outputs);
        } else {
            updated.updateVariables(outputs);
        }
        processRepository.save(updated);
        if (target.isContainer()) {
            stopIterations(target, steps);
        }

        target.logInfo("Step " + target.getStepId() + " completed by hand (was " + wasStatus + ").");
        target.updateStatus(StepExecutionStatus.COMPLETED);
        stepExecutionRepository.save(target);
        messageSubscriptionService.rearm(updated);

        audit.performed(updated, target.id(), OperatorAudit.COMPLETE_STEP, command.requestedBy(), command.reason(),
                "completed step " + target.getStepId() + " (was " + wasStatus + ")"
                        + (outputs.isEmpty() ? "" : " with "
                        + String.join(", ", outputs.stream().map(v -> v.name() + " = " + OperatorAudit.quote(v.value())).toList()))
                        + (target.isIteration() ? " (iteration " + target.getItemIndex() + " of its forEach)" : "")
                        + (reopens ? "; the process is running again" : ""));

        if (!ProcessStatus.PAUSED.equals(updated.getStatus())) {
            stepOverProcessUseCase.handle(new StepOverProcessCommand(process.getId()));
        }
        var step = ProcessOperationRules.stepOf(target);
        return step != null && StepType.PROCESS.equals(step.type()) ? target : null;
    }
}
