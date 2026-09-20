package io.mateu.workflow.worker.kafka;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.worker.WorkerReply;
import io.mateu.workflow.worker.api.ReplyNotAcceptedException;
import io.mateu.workflow.worker.api.TaskReplySink;
import java.util.List;
import org.springframework.cloud.stream.function.StreamOperations;

/**
 * Answers the engine over Kafka via {@link WorkerReply}, which publishes to the {@code upstream}
 * binding with its own bounded retry. When the broker still refuses after those retries WorkerReply
 * throws its {@code ReplyNotAcceptedException}; this translates it to the runtime's
 * {@link ReplyNotAcceptedException}, the one signal the dispatcher re-raises so the consumer leaves
 * the offset uncommitted and the task is redelivered rather than lost.
 */
final class WorkerReplySink implements TaskReplySink {

    private final StreamOperations streamBridge;

    WorkerReplySink(StreamOperations streamBridge) {
        this.streamBridge = streamBridge;
    }

    @Override
    public void running(TaskExecutionRequested task) {
        try {
            WorkerReply.running(streamBridge, task);
        } catch (WorkerReply.ReplyNotAcceptedException e) {
            throw redelivery(e);
        }
    }

    @Override
    public void completed(TaskExecutionRequested task, List<Variable> variables) {
        try {
            WorkerReply.completed(streamBridge, task, variables);
        } catch (WorkerReply.ReplyNotAcceptedException e) {
            throw redelivery(e);
        }
    }

    @Override
    public void failed(TaskExecutionRequested task, List<Variable> variables, String reason) {
        try {
            WorkerReply.failed(streamBridge, task, variables, reason);
        } catch (WorkerReply.ReplyNotAcceptedException e) {
            throw redelivery(e);
        }
    }

    private static ReplyNotAcceptedException redelivery(WorkerReply.ReplyNotAcceptedException e) {
        return new ReplyNotAcceptedException(e.getMessage(), e);
    }
}
