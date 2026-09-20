package io.mateu.workflow.worker.api;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Wraps a {@link TaskReplySink} so a terminal reply ({@code completed}/{@code failed}) is sent in
 * {@code afterCommit} when a transaction is active, and never inside it — so the engine only learns
 * a task is done once the work it did is durable (decision 9). Progress ({@code running}) is sent
 * immediately: it resets the lease clock while the task is still running, so deferring it to commit
 * would defeat its purpose.
 *
 * <p>With no active transaction (the Kafka worker's usual case) every reply is sent straight away.
 */
public final class TransactionAwareReplySink implements TaskReplySink {

    private final TaskReplySink delegate;

    public TransactionAwareReplySink(TaskReplySink delegate) {
        this.delegate = delegate;
    }

    @Override
    public void running(TaskExecutionRequested task) {
        delegate.running(task);
    }

    @Override
    public void completed(TaskExecutionRequested task, List<Variable> variables) {
        afterCommitOrNow(() -> delegate.completed(task, variables));
    }

    @Override
    public void failed(TaskExecutionRequested task, List<Variable> variables, String reason) {
        afterCommitOrNow(() -> delegate.failed(task, variables, reason));
    }

    private void afterCommitOrNow(Runnable reply) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    reply.run();
                }
            });
        } else {
            reply.run();
        }
    }
}
