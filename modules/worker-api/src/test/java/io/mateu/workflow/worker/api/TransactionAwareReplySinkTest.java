package io.mateu.workflow.worker.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TransactionAwareReplySinkTest {

    private final List<String> delivered = new ArrayList<>();
    private final TaskReplySink delegate = new TaskReplySink() {
        public void running(TaskExecutionRequested task) {
            delivered.add("running");
        }

        public void completed(TaskExecutionRequested task, List<Variable> variables) {
            delivered.add("completed");
        }

        public void failed(TaskExecutionRequested task, List<Variable> variables, String reason) {
            delivered.add("failed:" + reason);
        }
    };
    private final TaskReplySink sink = new TransactionAwareReplySink(delegate);
    private final TaskExecutionRequested task =
            new TaskExecutionRequested("tx", "p", "wf", "s", "t@1", List.of());

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void without_a_transaction_every_reply_is_immediate() {
        sink.completed(task, List.of());
        sink.failed(task, List.of(), "nope");
        assertThat(delivered).containsExactly("completed", "failed:nope");
    }

    @Test
    void with_a_transaction_terminal_replies_wait_for_commit() {
        TransactionSynchronizationManager.initSynchronization();

        sink.running(task);       // progress is immediate even in a transaction
        sink.completed(task, List.of());

        assertThat(delivered).containsExactly("running");

        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
        assertThat(delivered).containsExactly("running", "completed");
    }
}
