package io.mateu.workflow.infra.in.startup;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Arms the derived lookup state — the deadline and the message subscription — of steps that were
 * already in flight when the engine was upgraded to a version that stores them.
 *
 * <p>The engine now finds work by querying those fields instead of walking every live step, so a
 * step that started under an older version, and therefore carries neither, would wait forever:
 * its TIMER would never fire, its timeout would never expire, and no message would ever reach it.
 * This makes one pass at boot and rearms them from the state they already carry — {@code
 * startedAt}, the step JSON and the process — which is all those fields ever derived from. SQL
 * cannot do it in a migration: the TIMER date and the correlation key are read out of JSON, the
 * latter through a JEXL expression.
 *
 * <p>Idempotent and self-healing. On an engine that has only ever run this version every step is
 * already armed, nothing is written, and the cost is a single query at startup — not one per scan
 * tick, which is the whole point of materialising these fields.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InFlightStepRearmRunner implements ApplicationRunner {

    final StepExecutionRepository stepExecutionRepository;
    final ProcessRepository processRepository;

    @Override
    public void run(ApplicationArguments args) {
        var rearmed = 0;
        for (var stepExecution : stepExecutionRepository.findPendingOrRunning()) {
            var process = processRepository.findById(stepExecution.getProcessId()).orElse(null);
            if (process == null) {
                continue;
            }
            var updated = stepExecution.rearmedFor(process);
            if (updated != stepExecution) {
                stepExecutionRepository.save(updated);
                rearmed++;
            }
        }
        if (rearmed > 0) {
            log.info("Armed the lookup state of {} step execution(s) that were in flight before this upgrade",
                    rearmed);
        }
    }
}
