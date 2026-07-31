package io.mateu.workflow.application.usecases.process.childcancel;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessCommand;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Set;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Propagates cancellation parent→child: when a PROCESS step execution reaches a terminal
 * status other than COMPLETED (CANCELLED, ERROR or TIMEOUT), the child process it spawned
 * must not keep running for a parent that will never consume its result. Must be called from
 * every seam where a step execution is saved with one of those statuses.
 *
 * <p>The child is found through its deterministic businessKey
 * {@code "parent:" + stepExecutionId} and cancelled through {@link CancelProcessUseCase},
 * which cancels the child's own PROCESS steps in turn — each of those re-enters this
 * service, which is exactly how the cascade reaches grandchildren.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CancelChildProcessService {

    private static final Set<StepExecutionStatus> CHILD_CANCELLING_STATUSES = Set.of(
            StepExecutionStatus.CANCELLED,
            StepExecutionStatus.ERROR,
            StepExecutionStatus.TIMEOUT);

    final ProcessRepository processRepository;
    // ObjectProvider (not direct injection): CancelProcessUseCase depends on this service to
    // cascade into grandchildren, so a direct constructor dependency would close a cycle.
    final ObjectProvider<CancelProcessUseCase> cancelProcessUseCase;

    public void stepReachedTerminalStatus(StepExecution stepExecution) {
        if (stepExecution == null
                || !CHILD_CANCELLING_STATUSES.contains(stepExecution.getStatus())
                || stepExecution.getStepJson() == null) {
            // Only parse stepJson when the saved status is one of the three — this method
            // sits on hot save paths.
            return;
        }
        var step = pojoFromJson(stepExecution.getStepJson(), Step.class);
        if (!StepType.PROCESS.equals(step.type())) {
            return;
        }
        processRepository.findByBusinessKey("parent:" + stepExecution.id())
                .filter(child -> ProcessStatus.PENDING.equals(child.getStatus())
                        || ProcessStatus.RUNNING.equals(child.getStatus()))
                .ifPresent(child -> {
                    log.info("Parent PROCESS step {} ended {} — cancelling child process {}",
                            stepExecution.id(), stepExecution.getStatus(), child.getId());
                    cancelProcessUseCase.getObject().handle(new CancelProcessCommand(child.getId()));
                });
    }
}
