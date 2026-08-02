package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * E2E-LOCK-01..03 — Optimistic locking on the aggregates a running process mutates.
 *
 * <p>Events are keyed by process and a consumer group gives each partition to exactly one
 * consumer, so one pod owns a process. That guarantee has a hole: it covers which consumer is
 * <em>assigned</em> a partition, not which is still <em>in flight</em>. During a rebalance the
 * outgoing pod can be finishing a record the incoming one has just been handed.
 *
 * <p>These pin the fence for that window. A writer holding a copy read before someone else
 * committed must be rejected rather than silently overwrite — which is the whole reason a version
 * can stand in for a pessimistic lock instead of sitting next to one.
 */
class OptimisticLockingE2eTest extends AbstractJpaE2eTest {

    /** E2E-LOCK-01 — a process write based on a stale read is rejected. */
    @Test
    void staleProcessWriteIsRejected() {
        createProcess("sequential-3", "lock-1");
        awaitStatus("lock-1", ProcessStatus.COMPLETED);

        var asReadByTheOutgoingPod = process("lock-1");
        assertThat(asReadByTheOutgoingPod.getVersion())
                .as("a persisted process must carry a version, or there is nothing to fence with")
                .isNotNull();

        // The incoming owner commits first.
        processRepository.save(process("lock-1")
                .withName("written by the new owner"));

        // The outgoing pod finishes its record and writes what it read before that.
        assertThatThrownBy(() -> processRepository.save(
                asReadByTheOutgoingPod.withName("written by the stale owner")))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(process("lock-1").getName()).isEqualTo("written by the new owner");
    }

    /** E2E-LOCK-02 — the same for a step execution. */
    @Test
    void staleStepExecutionWriteIsRejected() {
        createProcess("sequential-3", "lock-2");
        awaitStatus("lock-2", ProcessStatus.COMPLETED);

        var stale = steps("lock-2").get(0);
        assertThat(stale.getVersion()).isNotNull();

        stepExecutionRepository.save(stepExecutionRepository.findById(stale.id()).orElseThrow()
                .withWorkerId("the new owner"));

        assertThatThrownBy(() -> stepExecutionRepository.save(stale.withWorkerId("the stale owner")))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    /**
     * E2E-LOCK-03 — a fresh aggregate still inserts. The version doubles as Spring Data's
     * "never persisted" signal, so getting this wrong would turn every creation into an attempt
     * to update a row that is not there.
     */
    @Test
    void aNeverPersistedAggregateStillInserts() {
        assertThatCode(() -> {
            createProcess("sequential-3", "lock-3");
            awaitStatus("lock-3", ProcessStatus.COMPLETED);
        }).doesNotThrowAnyException();

        assertThat(process("lock-3").getVersion()).isNotNull();
        assertThat(steps("lock-3"))
                .isNotEmpty()
                .allSatisfy(step -> {
                    assertThat(step.getVersion()).isNotNull();
                    assertThat(step.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
                });
    }
}
