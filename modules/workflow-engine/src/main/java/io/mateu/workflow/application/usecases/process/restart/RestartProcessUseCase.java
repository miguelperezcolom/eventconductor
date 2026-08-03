package io.mateu.workflow.application.usecases.process.restart;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.Variable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Runs a stopped process again from the top: every step goes back to the state it was created in
 * and the process starts over, including the steps that had already succeeded.
 *
 * <p>The other half of {@link io.mateu.workflow.application.usecases.process.retry
 * .RetryProcessUseCase}, and the choice between them is an operator's to make. Picking up where it
 * failed is right when the failure was the environment — a worker that was down, a downstream
 * service that has since recovered. Starting over is right when the run itself was wrong, or when
 * the steps that succeeded left nothing behind that a second run would trip over.
 *
 * <h2>The same process, not a new one</h2>
 *
 * <p>It resets this instance rather than creating another, and that is not a shortcut: a process
 * carries a business key, the key is how the outside world refers to it, and creating a second
 * process under the same one is refused by design (see {@code CreateProcessUseCase}). The
 * definition it re-runs is the snapshot frozen into the process at birth, so a restart runs the
 * workflow this instance was created from even if the definition has since changed — the same
 * guarantee the instance had on its first run.
 *
 * <h2>The variables it starts from</h2>
 *
 * <p>Workers write variables back onto the process as it runs, so by the time it fails the
 * variables are the failed run's, not the ones it was given. Re-running from those would not be
 * "from the beginning": a guard reading a variable a later step wrote would take a branch on the
 * second run that it could not have taken on the first. The engine does keep the original,
 * though, without meaning to — the first step to start froze the process's variables as they were
 * then — so that is what this restores. A process whose first step never started has nothing to
 * restore and keeps what it has.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RestartProcessUseCase {

    /** Same statuses a retry accepts: a process that stopped, and did not finish. */
    private static final Set<ProcessStatus> RESTARTABLE =
            EnumSet.of(ProcessStatus.ERROR, ProcessStatus.CANCELLED);

    final ProcessRepository processRepository;
    final StepExecutionRepository stepExecutionRepository;
    final StepOverProcessUseCase stepOverProcessUseCase;
    final WorkflowMetrics workflowMetrics;

    public void handle(RestartProcessCommand command) {
        var process = processRepository.findById(command.processId()).orElseThrow();
        if (!RESTARTABLE.contains(process.getStatus())) {
            log.warn("Process {} cannot be restarted from status {} — ignoring",
                    process.getId(), process.getStatus());
            return;
        }

        var stepExecutions = stepExecutionRepository.findByProcess(process);
        // Read before resetting: the reset is what clears the startedAt this reads.
        var initialVariables = variablesTheProcessStartedWith(stepExecutions);

        for (var stepExecution : stepExecutions) {
            stepExecution.resetForRerun();
            stepExecutionRepository.save(stepExecution);
        }
        workflowMetrics.retryPerformed(process.getWorkflowDefinitionId(),
                WorkflowMetrics.RetryTrigger.MANUAL);

        var restarted = process
                .withStatus(ProcessStatus.RUNNING)
                .withFinished(null)
                .withCompletionPercentage(0);
        if (initialVariables != null) {
            restarted = restarted.withVariables(initialVariables);
        }
        processRepository.save(restarted);

        log.info("Process {} restarted from the beginning: {} step execution(s) reset",
                process.getId(), stepExecutions.size());

        stepOverProcessUseCase.handle(new StepOverProcessCommand(process.getId()));
    }

    /**
     * The process variables as they were when it began, taken from the first step that started —
     * {@code start} freezes them onto the step — or null when no step ever did.
     */
    private List<Variable> variablesTheProcessStartedWith(List<StepExecution> stepExecutions) {
        return stepExecutions.stream()
                .filter(stepExecution -> stepExecution.getStartedAt() != null)
                .min(Comparator.comparingLong(StepExecution::getOrder))
                .map(StepExecution::getVariables)
                .orElse(null);
    }
}
