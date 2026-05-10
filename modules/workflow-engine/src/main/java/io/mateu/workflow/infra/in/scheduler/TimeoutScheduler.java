package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.events.integration.TimeoutCheckRequested;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Periodically scans running step executions and emits TimeoutCheckRequested events.
 * Only active in JPA mode (requires JdbcTemplate for the advisory lock).
 * The advisory lock (777888999L) ensures only one pod runs the scan at a time.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class TimeoutScheduler {

    // Must differ from WorkflowOrchestrator.LOCK_ID (123456789L),
    // OutboxRelay.LOCK_ID (111222333L), and EmbeddedOutboxRelay.LOCK_ID (444555666L)
    private static final long LOCK_ID = 777888999L;

    final StepExecutionRepository stepExecutionRepository;
    final UpstreamEventPublisher upstreamEventPublisher;
    final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void start() {
        new Thread(() -> {
            try {
                while (true) {
                    try {
                        Boolean acquired = jdbcTemplate.queryForObject(
                                "SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_ID);
                        if (Boolean.TRUE.equals(acquired)) {
                            try {
                                stepExecutionRepository.findPendingOrRunning().forEach(se -> {
                                    if (StepExecutionStatus.RUNNING.equals(se.getStatus())) {
                                        var step = pojoFromJson(se.getStepJson(), Step.class);
                                        if (step.timeout() > 0) {
                                            upstreamEventPublisher.publish(new TimeoutCheckRequested(se.getProcessId()));
                                        }
                                    }
                                });
                            } finally {
                                jdbcTemplate.queryForObject(
                                        "SELECT pg_advisory_unlock(?)", Boolean.class, LOCK_ID);
                            }
                        }
                    } catch (Throwable e) {
                        log.error("Error checking step timeouts", e);
                    }
                    Thread.sleep(10_000);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }).start();
    }

}
