package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.WorkflowMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exclusivity in kafka mode, where it is not taken but inherited.
 *
 * <p>Events are keyed by process, so every event of a process goes to one partition and a
 * consumer group gives that partition to exactly one consumer. A process therefore has a single
 * writer by construction, and there is nothing left for a lock to arrange: this only opens the
 * transaction the work needs to commit atomically.
 *
 * <p>What guards the gap is the optimistic version on the aggregates. Kafka's guarantee is about
 * which consumer is <em>assigned</em> a partition, not which is still <em>in flight</em>, so
 * during a rebalance the outgoing pod can be finishing a record the incoming one now owns; its
 * write is then rejected instead of overwriting. The same catch covers the deliberate fallback
 * for workers that report back without echoing a process — those events are unkeyed and can land
 * anywhere.
 *
 * <p><b>The version guards a row, not a decision.</b> Two writers that read the same state and
 * then write <em>different</em> rows — two step-overs both concluding the next step should start
 * — do not collide on any version and would dispatch it twice. Under real ownership that cannot
 * arise; it is the reason {@code embedded} mode keeps the row lock, since nothing partitions
 * processes across pods there.
 */
@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "kafka")
@RequiredArgsConstructor
@Slf4j
public class PartitionOwnedProcessLockService implements ProcessLockService {

    final TransactionTemplate transactionTemplate;
    final WorkflowMetrics workflowMetrics;

    @Override
    public boolean runExclusively(String processId, Runnable action) {
        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                action.run();
                return true;
            }));
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            workflowMetrics.concurrentWriteRejected(processId);
            log.warn("Concurrent write to process {} was rejected; the event will be redelivered",
                    processId);
            return false;
        }
    }
}
