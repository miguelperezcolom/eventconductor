package io.mateu.workflow.infra.in.startup;

import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;

/**
 * Arms the derived lookup state — the deadline and the message subscription — of steps that were
 * already in flight when the engine was upgraded to a version that stores them.
 *
 * <p>The engine finds work by querying those fields instead of walking every live step, so a step
 * that started under an older version, and therefore carries neither, would wait forever: its
 * TIMER would never fire, its timeout would never expire, and no message would ever reach it.
 * This rearms them from the state they already carry — {@code startedAt}, the step JSON and the
 * process — which is all those fields ever derived from. SQL cannot do it in a migration: the
 * TIMER date and the correlation key are read out of JSON, the latter through a JEXL expression.
 *
 * <p><b>It must not hold up the boot, and must not need a database to reach it.</b> A pod is
 * expected to start with PostgreSQL unavailable and pick up its work when the database returns
 * (DIST-08), so this runs on a daemon thread that retries until one pass gets through rather
 * than failing the context.
 *
 * <p>Writes go through the process lock and re-read the step under it: on a multi-pod cluster
 * another pod may be mid-flight on the same process, and saving a copy read before that would
 * roll its status back. In steady state nothing needs arming, so the whole pass is one query —
 * no locks, no writes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InFlightStepRearmRunner implements ApplicationRunner {

    private static final long RETRY_DELAY_MS = 5_000;

    final StepExecutionRepository stepExecutionRepository;
    final ProcessRepository processRepository;
    final ProcessLockService processLockService;

    @Override
    public void run(ApplicationArguments args) {
        var thread = new Thread(this::rearmWhenTheDatabaseAllows, "step-rearm");
        thread.setDaemon(true);
        thread.start();
    }

    private void rearmWhenTheDatabaseAllows() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                rearmOnce();
                return;
            } catch (Throwable e) {
                log.warn("Could not arm the lookup state of in-flight steps yet ({}) — retrying in {}ms",
                        e.getMessage(), RETRY_DELAY_MS);
            }
            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** One pass. Package-private so a test can drive it without the retry thread around it. */
    void rearmOnce() {
        var processIds = new LinkedHashSet<String>();
        for (var stepExecution : stepExecutionRepository.findPendingOrRunning()) {
            var process = processRepository.findById(stepExecution.getProcessId()).orElse(null);
            if (process != null && stepExecution.rearmedFor(process) != stepExecution) {
                processIds.add(process.id());
            }
        }
        if (processIds.isEmpty()) {
            return;
        }
        var armed = 0;
        for (var processId : processIds) {
            armed += rearm(processId);
        }
        log.info("Armed the lookup state of {} step execution(s) that were in flight before this upgrade",
                armed);
    }

    private int rearm(String processId) {
        var armed = new java.util.concurrent.atomic.AtomicInteger();
        if (!processLockService.runExclusively(processId, () -> armed.set(armStepsOf(processId)))) {
            log.warn("Could not lock process {} to arm its step lookup state — the next start will retry",
                    processId);
            return 0;
        }
        return armed.get();
    }

    private int armStepsOf(String processId) {
        var process = processRepository.findById(processId).orElse(null);
        if (process == null) {
            return 0;
        }
        var armed = 0;
        for (var stepExecution : stepExecutionRepository.findPendingOrRunningByProcessId(processId)) {
            var updated = stepExecution.rearmedFor(process);
            if (updated != stepExecution) {
                stepExecutionRepository.save(updated);
                armed++;
            }
        }
        return armed;
    }
}
