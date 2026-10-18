package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.ConcurrentProcessAccessException;
import io.mateu.workflow.application.out.PoisonEventException;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.EventFailures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Only "the row lock could not be had" (and a lost optimistic race) reads as "not obtained". A
 * failure of the work itself must reach the caller as itself: swallowed into {@code false}, it
 * became a cause-less {@link ConcurrentProcessAccessException} — retryable — so a poison event was
 * retried by the relay forever instead of being parked.
 */
class JdbcProcessLockServiceTest {

    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    WorkflowMetrics workflowMetrics = mock(WorkflowMetrics.class);
    JdbcProcessLockService lockService;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lockService = new JdbcProcessLockService(jdbcTemplate, new TransactionTemplate(transactionManager),
                workflowMetrics);
        lockService.lockTimeoutSeconds = 1;
    }

    @Test
    void runsTheActionOnceTheRowIsLocked() {
        var ran = new AtomicBoolean();
        assertThat(lockService.runExclusively("p-1", () -> ran.set(true))).isTrue();
        assertThat(ran).isTrue();
    }

    @Test
    void aRowLockThatCannotBeHadIsNotObtainedAndTheActionDoesNotRun() {
        when(jdbcTemplate.execute(any(ConnectionCallback.class)))
                .thenThrow(new QueryTimeoutException("statement timeout waiting for the row"));
        var ran = new AtomicBoolean();

        assertThat(lockService.runExclusively("p-1", () -> ran.set(true))).isFalse();
        assertThat(ran).isFalse();
        var status = org.mockito.ArgumentCaptor.forClass(org.springframework.transaction.TransactionStatus.class);
        verify(transactionManager).commit(status.capture());
        assertThat(status.getValue().isRollbackOnly()).as("nothing of it may be kept").isTrue();
    }

    @Test
    void aLostOptimisticRaceIsNotObtained() {
        assertThat(lockService.runExclusively("p-1", () -> {
            throw new OptimisticLockingFailureException("stale version");
        })).isFalse();
        verify(workflowMetrics).concurrentWriteRejected("p-1");
    }

    @Test
    void aPoisonFailureOfTheActionPropagatesAndIsParkedNotRetried() {
        var poison = new PoisonEventException("step execution se-404 does not exist");

        assertThatThrownBy(() -> ProcessLockService.runExclusivelyOrThrow(lockService, "p-1", () -> {
            throw poison;
        })).isSameAs(poison)
                .satisfies(thrown -> assertThat(EventFailures.isRetryable(thrown)).isFalse());
    }

    @Test
    void aDefectInTheActionPropagatesAsItself() {
        var defect = new IllegalStateException("bug");

        assertThatThrownBy(() -> lockService.runExclusively("p-1", () -> {
            throw defect;
        })).isSameAs(defect);
        verify(transactionManager).rollback(any());
    }

    @Test
    void aTransientFailureOfTheActionPropagatesAndStaysRetryable() {
        var deadlock = new CannotAcquireLockException("deadlock detected");

        assertThatThrownBy(() -> lockService.runExclusively("p-1", () -> {
            throw deadlock;
        })).isSameAs(deadlock)
                .satisfies(thrown -> assertThat(EventFailures.isRetryable(thrown)).isTrue());
    }
}
