package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.Process;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Keeps the materialised message subscription of a process's waiting steps in step with the
 * process itself.
 *
 * <p>A WAIT_FOR_MESSAGE step stores the correlation key it expects so an arriving message can
 * find it by index. That key is a JEXL expression over process variables, and those move while
 * the step waits — a parallel branch completing can change the very variable the expression
 * reads. Evaluating on arrival made that free; storing it does not, so every path that updates
 * process variables passes through here and the stored key never lags the process it describes.
 *
 * <p>Scoped to the one process whose variables changed, and it writes only the steps whose key
 * actually moved — normally none.
 */
@Service
@RequiredArgsConstructor
public class MessageSubscriptionService {

    final StepExecutionRepository stepExecutionRepository;

    public void rearm(Process process) {
        stepExecutionRepository.findPendingOrRunningByProcessId(process.id()).forEach(stepExecution -> {
            var rearmed = stepExecution.rearmedFor(process);
            if (rearmed != stepExecution) {
                stepExecutionRepository.save(rearmed);
            }
        });
    }
}
