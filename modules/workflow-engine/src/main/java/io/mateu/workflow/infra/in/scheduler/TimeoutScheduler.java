package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.dtos.events.integration.TimeoutCheckRequested;
import io.mateu.workflow.dtos.events.integration.TimerCheckRequested;
import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Periodically asks the repository for the step executions whose deadline has passed and emits
 * TimeoutCheckRequested events for expired step timeouts and TimerCheckRequested events for due
 * TIMER steps. Only active in JPA mode (requires JdbcTemplate for the advisory lock).
 * The advisory lock (777888999L) ensures only one pod runs the query at a time.
 *
 * <p>The query is an indexed range scan over the materialised deadline, so its cost tracks the
 * work that is due — normally none — and not the number of steps waiting. That is what keeps the
 * engine's own workload (long waits: a TIMER pending for weeks) free between tick and due moment,
 * and it is why the single-pod lock is not a bottleneck: there is almost nothing to hold it for.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
@Slf4j
public class TimeoutScheduler {

    // Advisory lock ids in use: 222333444 (CronStartScheduler),
    // 444555666 (EmbeddedOutboxRelay), 777888999 (here). Keep them distinct.
    private static final long LOCK_ID = 777888999L;

    final StepExecutionRepository stepExecutionRepository;
    final UpstreamEventPublisher upstreamEventPublisher;
    final JdbcTemplate jdbcTemplate;
    final DbLockDialect dbLockDialect;

    final WorkflowMetrics workflowMetrics;

    @org.springframework.beans.factory.annotation.Value("${workflow.timeout-scan-interval-ms:10000}")
    long scanIntervalMs;

    /**
     * How long a live step with no deadline has to sit before it is counted as stalled. Long
     * enough that ordinary slow work is not reported, short enough that a lost dispatch shows up
     * the same day.
     */
    @org.springframework.beans.factory.annotation.Value("${workflow.stalled-step-after-ms:900000}")
    long stalledAfterMs;

    @org.springframework.beans.factory.annotation.Value("${workflow.stalled-step-scan-interval-ms:60000}")
    long stalledScanIntervalMs;

    @PostConstruct
    public void start() {
        var thread = new Thread(() -> {
            try {
                while (true) {
                    try {
                        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
                            if (!dbLockDialect.tryLock(con, LOCK_ID)) return null;
                            try {
                                var now = java.time.LocalDateTime.now();
                                var deadlines = StepDeadlines.classify(stepExecutionRepository.findDue(now));
                                deadlines.timedOutProcessIds().forEach(processId ->
                                        upstreamEventPublisher.publish(new TimeoutCheckRequested(processId)));
                                deadlines.dueTimerProcessIds().forEach(processId ->
                                        upstreamEventPublisher.publish(new TimerCheckRequested(processId)));
                            } finally {
                                dbLockDialect.unlock(con, LOCK_ID);
                            }
                            return null;
                        });
                    } catch (Throwable e) {
                        log.error("Error checking step timeouts", e);
                    }
                    Thread.sleep(scanIntervalMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "workflow-timeout-scheduler");
        thread.setDaemon(true);
        thread.start();
        startStalledStepWatch();
    }

    /**
     * Reports live steps that carry no deadline and have been waiting a long time.
     *
     * <p>The scan above is an index range over the deadline, which is what makes it cheap — and
     * which means a step without a deadline is not merely unhandled, it is unobservable. If its
     * dispatch or its worker's reply is lost, it waits forever and nothing in the engine has any
     * reason to look at it again. That is how a broker outage left 3 356 processes permanently
     * stopped with not one line in a log to show for it.
     *
     * <p>Reporting only. The fix for a stalled step is to give it a timeout, either on the step
     * or through {@code workflow.default-step-timeout-ms}; this exists so that not having done
     * so is visible rather than silent. Runs on its own slower loop and takes no lock — a count
     * costs the same on every pod, and the gauge is per-pod anyway.
     */
    private void startStalledStepWatch() {
        var thread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(stalledScanIntervalMs);
                    var stalled = stepExecutionRepository.countStalled(
                            java.time.LocalDateTime.now().minusNanos(stalledAfterMs * 1_000_000));
                    workflowMetrics.stalledStepsObserved(stalled);
                    if (stalled > 0) {
                        log.warn("{} step executions have been waiting more than {} ms with no "
                                        + "deadline: nothing will ever time them out. Give those steps a "
                                        + "timeout, or set workflow.default-step-timeout-ms.",
                                stalled, stalledAfterMs);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable e) {
                    log.error("Error counting stalled step executions", e);
                }
            }
        }, "workflow-stalled-step-watch");
        thread.setDaemon(true);
        thread.start();
    }

}
