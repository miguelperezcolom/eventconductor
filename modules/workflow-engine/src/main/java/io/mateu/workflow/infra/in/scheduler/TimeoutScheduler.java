package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.events.integration.TimeoutCheckRequested;
import io.mateu.workflow.infra.out.persistence.DbLockDialect;
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
    final DbLockDialect dbLockDialect;

    @PostConstruct
    public void start() {
        new Thread(() -> {
            try {
                while (true) {
                    try {
                        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
                            if (!dbLockDialect.tryLock(con, LOCK_ID)) return null;
                            try {
                                stepExecutionRepository.findPendingOrRunning().forEach(se -> {
                                    var step = pojoFromJson(se.getStepJson(), Step.class);
                                    if (step.timeout() > 0) {
                                        upstreamEventPublisher.publish(new TimeoutCheckRequested(se.getProcessId()));
                                    }
                                });
                            } finally {
                                dbLockDialect.unlock(con, LOCK_ID);
                            }
                            return null;
                        });
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
