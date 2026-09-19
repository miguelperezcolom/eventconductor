package io.mateu.workflow.application.usecases.lock;

import io.mateu.workflow.application.out.LockService;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Force-releases held locks whose lease has expired and wakes whoever each one is handed to — the
 * crash backstop for a lock whose holder died without reaching its UNLOCK or a terminal state. The
 * eviction and the waiter promotion are one transaction inside {@link LockService#expireLeases}; the
 * waking reuses the ordinary paths, so a step-level waiter's parked step is completed and a
 * process-level waiter is simply stepped over.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExpireLockLeasesUseCase {

    final LockService lockService;
    // ObjectProviders for the same injection-cycle reason StepOverProcessUseCase uses them.
    final ObjectProvider<UpdateStepExecutionUseCase> updateStepExecutionUseCase;
    final ObjectProvider<StepOverProcessUseCase> stepOverProcessUseCase;

    public void handle() {
        var grants = lockService.expireLeases(LocalDateTime.now());
        if (!grants.isEmpty()) {
            log.warn("Reaped {} expired lock lease(s); admitting the next waiter for each", grants.size());
        }
        grants.forEach(this::wake);
    }

    private void wake(LockService.Grant grant) {
        if (grant.stepExecutionId() != null) {
            updateStepExecutionUseCase.getObject().handle(new UpdateStepExecutionCommand(
                    grant.stepExecutionId(), List.of(),
                    "Lock '" + grant.lockName() + "' on '" + grant.lockKey() + "' acquired (previous holder's lease expired)",
                    StepExecutionStatus.COMPLETED));
        } else {
            stepOverProcessUseCase.getObject().handle(new StepOverProcessCommand(grant.processId()));
        }
    }
}
