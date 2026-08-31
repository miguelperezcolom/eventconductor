package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.dtos.events.integration.RetryDueCheckRequested;
import io.mateu.workflow.dtos.events.integration.TimeoutCheckRequested;
import io.mateu.workflow.dtos.events.integration.TimerCheckRequested;
import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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
    final io.mateu.workflow.application.out.ProcessRepository processRepository;
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

    /**
     * How long a running process with nothing left to run has to sit before it is reported. Longer
     * than the step threshold on purpose: a process legitimately passes through this shape for the
     * moment between one step finishing and the next being dispatched, and a threshold in seconds
     * would report every process in flight.
     */
    @org.springframework.beans.factory.annotation.Value("${workflow.stalled-process-after-ms:1800000}")
    long stalledProcessAfterMs;

    @org.springframework.beans.factory.annotation.Value("${workflow.stalled-process-scan-interval-ms:300000}")
    long stalledProcessScanIntervalMs;

    /** Ceiling on the ids one report names; see ProcessRepository#findStalled. */
    private static final int STALLED_PROCESS_REPORT_LIMIT = 20;

    private volatile boolean running = true;
    private Thread timeoutSchedulerThread;
    private Thread stalledStepWatchThread;
    private Thread stalledProcessWatchThread;

    @PostConstruct
    public void start() {
        timeoutSchedulerThread = new Thread(() -> {
            try {
                while (running) {
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
                                deadlines.dueRetryProcessIds().forEach(processId ->
                                        upstreamEventPublisher.publish(new RetryDueCheckRequested(processId)));
                            } finally {
                                dbLockDialect.unlock(con, LOCK_ID);
                            }
                            return null;
                        });
                    } catch (Throwable e) {
                        if (running) {
                            log.error("Error checking step timeouts", e);
                        }
                    }
                    if (running) {
                        Thread.sleep(scanIntervalMs);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "workflow-timeout-scheduler");
        timeoutSchedulerThread.setDaemon(true);
        timeoutSchedulerThread.start();
        startStalledStepWatch();
        startStalledProcessWatch();
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
     * <p>Only ACTION and RULE steps are counted — the ones a worker owes an answer for. Everything
     * else that waits without a deadline does so on purpose, and counting those made the number
     * useless: any deployment with human tasks reported permanent stalled work, once a minute, on
     * every pod.
     *
     * <p>Reporting only. The fix for a stalled step is to give it a timeout, either on the step
     * or through {@code workflow.default-step-timeout-ms}; this exists so that not having done
     * so is visible rather than silent. Runs on its own slower loop and takes no lock — a count
     * costs the same on every pod.
     *
     * <p><b>The number is cluster-wide, and every pod reports it.</b> It counts rows in a shared
     * table, not this pod's share of anything, so N pods publish the same figure: alert on the
     * maximum across replicas, never the sum, which would multiply it by the replica count.
     */
    private void startStalledStepWatch() {
        stalledStepWatchThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(stalledScanIntervalMs);
                    var stalled = stepExecutionRepository.countStalled(
                            java.time.LocalDateTime.now().minusNanos(stalledAfterMs * 1_000_000));
                    workflowMetrics.stalledStepsObserved(stalled);
                    if (stalled > 0) {
                        log.warn("{} ACTION/RULE step execution(s) have been waiting more than {} ms "
                                        + "for a worker with no deadline: nothing will ever time them "
                                        + "out. Give those steps a timeout, or set "
                                        + "workflow.default-step-timeout-ms.",
                                stalled, stalledAfterMs);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable e) {
                    if (running) {
                        log.error("Error counting stalled step executions", e);
                    }
                }
            }
        }, "workflow-stalled-step-watch");
        stalledStepWatchThread.setDaemon(true);
        stalledStepWatchThread.start();
    }

    /**
     * Reports processes that are RUNNING with no step left to run.
     *
     * <p>The other two watches cannot see this one, and that is the point. The deadline scan is an
     * index range over {@code deadlineAt}, and a step that never started has none. The stalled-step
     * watch counts <em>live</em> steps a worker owes an answer for, and here there are none: every
     * step is either finished or was never eligible. So the engine had no clock, no queue and no
     * count that would ever mention such a process again.
     *
     * <p>It is what a branch with no matching guard looks like from outside: the process completes
     * a step, no successor becomes eligible, and it stops. Four processes on the reference
     * deployment sat like that for a week, and what found them was a person asking.
     *
     * <p>Reporting only, and the ids are named because a count is not actionable here — the repair
     * is a change to the definition, and you cannot make it without knowing which definition. It is
     * deliberately not a status change: marking them ERROR would lose the state that says what they
     * were waiting to match.
     *
     * <p>Its own loop, slower than the step watch, and no lock: like that one it counts rows in a
     * shared table, so every pod reports the same number. Alert on the maximum across replicas,
     * never the sum.
     */
    private void startStalledProcessWatch() {
        stalledProcessWatchThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(stalledProcessScanIntervalMs);
                    var stalled = processRepository.findStalled(
                            java.time.LocalDateTime.now().minusNanos(stalledProcessAfterMs * 1_000_000),
                            STALLED_PROCESS_REPORT_LIMIT);
                    workflowMetrics.stalledProcessesObserved(stalled.size());
                    if (!stalled.isEmpty()) {
                        log.warn("{} process(es) have been RUNNING with no step left to run for more "
                                        + "than {} ms. Nothing will time them out, because a step that "
                                        + "never started has no deadline: the last step completed and "
                                        + "no branch after it was eligible. Check the guards on what "
                                        + "follows the last completed step. Ids{}: {}",
                                stalled.size(), stalledProcessAfterMs,
                                stalled.size() == STALLED_PROCESS_REPORT_LIMIT ? " (first " + STALLED_PROCESS_REPORT_LIMIT + ")" : "",
                                String.join(", ", stalled));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable e) {
                    if (running) {
                        log.error("Error looking for stalled processes", e);
                    }
                }
            }
        }, "workflow-stalled-process-watch");
        stalledProcessWatchThread.setDaemon(true);
        stalledProcessWatchThread.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (timeoutSchedulerThread != null) {
            timeoutSchedulerThread.interrupt();
        }
        if (stalledStepWatchThread != null) {
            stalledStepWatchThread.interrupt();
        }
        if (stalledProcessWatchThread != null) {
            stalledProcessWatchThread.interrupt();
        }
    }

}
