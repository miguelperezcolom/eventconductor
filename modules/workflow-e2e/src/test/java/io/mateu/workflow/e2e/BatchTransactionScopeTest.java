package io.mateu.workflow.e2e;

import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Probe: what one failed unit does to the batch transaction around it.
 *
 * <p>Why a poll batch is committed per process rather than per batch. One transaction for the
 * whole batch is the obvious shape and it is a trap: an event that fails inside its own
 * participating transaction — the shape {@code runExclusively} has — marks the shared transaction
 * rollback-only even when the caller catches the failure and carries on. Every other event in
 * that batch then loses the commit it believed it had.
 *
 * <p>The failure that makes this concrete is an optimistic conflict, which is not exotic: it is
 * what a consumer-group rebalance produces, so the moment the engine is least settled is exactly
 * when a batch-wide transaction would throw away the most work.
 */
class BatchTransactionScopeTest extends AbstractJpaE2eTest {

    @Autowired TransactionTemplate transactionTemplate;

    @Test
    void oneHandledFailureWouldDoomAWholeBatchTransaction() {
        var carriedOn = new AtomicBoolean();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(batch -> {
            // One event fails and its failure is handled, exactly as runExclusively handles an
            // optimistic conflict: caught, counted, "the event will be redelivered".
            try {
                transactionTemplate.executeWithoutResult(one -> {
                    throw new OptimisticLockingFailureException("another writer had it");
                });
            } catch (OptimisticLockingFailureException handled) {
                // swallowed on purpose
            }
            // The rest of the batch proceeds, believing it committed.
            carriedOn.set(true);
        })).isInstanceOf(UnexpectedRollbackException.class);
    }
}
