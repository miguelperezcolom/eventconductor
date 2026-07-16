package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
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
 * Periodically scans running step executions and emits TimeoutCheckRequested events for
 * expired step timeouts and TimerCheckRequested events for due TIMER steps.
 * Only active in JPA mode (requires JdbcTemplate for the advisory lock).
 * The advisory lock (777888999L) ensures only one pod runs the scan at a time.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
@Slf4j
public class TimeoutScheduler {

    // Must differ from WorkflowOrchestrator.LOCK_ID (123456789L),
    // OutboxRelay.LOCK_ID (111222333L), EmbeddedOutboxRelay.LOCK_ID (444555666L)
    // and CronStartScheduler.LOCK_ID (222333444L)
    private static final long LOCK_ID = 777888999L;

    final StepExecutionRepository stepExecutionRepository;
    final UpstreamEventPublisher upstreamEventPublisher;
    final JdbcTemplate jdbcTemplate;
    final DbLockDialect dbLockDialect;

    @org.springframework.beans.factory.annotation.Value("${workflow.timeout-scan-interval-ms:10000}")
    long scanIntervalMs;

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
                                var deadlines = StepDeadlines.scan(stepExecutionRepository.findPendingOrRunning(), now);
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
    }

}
