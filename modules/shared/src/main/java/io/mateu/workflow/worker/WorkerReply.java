package io.mateu.workflow.worker;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import org.springframework.cloud.stream.function.StreamOperations;

import java.util.List;

/**
 * How a worker answers the engine, written down once so it stops being written wrong.
 *
 * <p>The obvious line —
 *
 * <pre>{@code streamBridge.send("upstream", new TaskStatusChanged(id, COMPLETED, vars, processId));}</pre>
 *
 * <p>— is what every worker in this repository did, and it loses work. {@code send} reports
 * failure by returning {@code false} and nobody looked; the listener then returns normally, the
 * consumer commits the offset, and the task the worker just finished is gone. Nothing retries it:
 * the engine's step sits in {@code PENDING} waiting for a reply that was never published, and a
 * step with no timeout waits there forever.
 *
 * <p>It is not a theoretical hole. On a four-hour run with a ninety-second broker outage in the
 * middle, workers consumed 230 417 task requests and published 227 065 replies. The missing 3 352
 * became 3 356 processes that will never finish, with nothing anywhere reporting a problem.
 *
 * <p>So: retry, and if the broker still will not take it, <b>throw</b>. Throwing is the point.
 * It leaves the offset uncommitted, so Kafka redelivers the task and the worker does it again —
 * which is why worker handlers have to be idempotent, and always did.
 *
 * <h2>One prerequisite</h2>
 *
 * <p>The producer binding must be synchronous, or the {@code false} this class checks for never
 * arrives — an asynchronous send returns {@code true} the moment the record is buffered:
 *
 * <pre>{@code spring.cloud.stream.kafka.default.producer.sync: true}</pre>
 *
 * <p>Applications that also run the engine get that default from the engine itself. A standalone
 * worker has to set it, and without it this class is decoration.
 *
 * <p>The parameter is {@link StreamOperations} rather than {@code StreamBridge} — the interface
 * the bridge implements. Callers pass their {@code StreamBridge} unchanged; the reason for the
 * wider type is that {@code StreamBridge} is final, and a retry policy nobody can write a test
 * for is not a retry policy anyone should trust.
 */
public final class WorkerReply {

    /** Attempts, including the first. Beyond this the task is redelivered rather than lost. */
    private static final int ATTEMPTS = 5;

    private static final long BASE_BACKOFF_MILLIS = 200;

    public static void completed(StreamOperations streamBridge, TaskExecutionRequested task,
                                 List<Variable> variables) {
        send(streamBridge, new TaskStatusChanged(
                task.taskExecutionId(), TaskStatus.COMPLETED, variables, task.processId()));
    }

    public static void running(StreamOperations streamBridge, TaskExecutionRequested task) {
        send(streamBridge, new TaskStatusChanged(
                task.taskExecutionId(), TaskStatus.RUNNING, List.of(), task.processId()));
    }

    public static void failed(StreamOperations streamBridge, TaskExecutionRequested task,
                              List<Variable> variables) {
        send(streamBridge, new TaskStatusChanged(
                task.taskExecutionId(), TaskStatus.ERROR, variables, task.processId()));
    }

    /**
     * Publishes the reply, retrying a refused send, and throws if the broker never takes it.
     *
     * @throws ReplyNotAcceptedException so the caller's listener fails and the offset is not
     *                                   committed. Do not catch it to "keep going" — that is the
     *                                   bug this class exists to prevent.
     */
    public static void send(StreamOperations streamBridge, TaskStatusChanged reply) {
        RuntimeException lastFailure = null;
        for (var attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                if (streamBridge.send("upstream", reply)) {
                    return;
                }
                lastFailure = null;
            } catch (RuntimeException e) {
                // A synchronous binding surfaces broker failures as exceptions as readily as it
                // does with a false return; both mean the same thing here.
                lastFailure = e;
            }
            if (attempt < ATTEMPTS && !backoff(attempt)) {
                break;
            }
        }
        throw new ReplyNotAcceptedException(reply, lastFailure);
    }

    /** @return false if the wait was interrupted, in which case there is no point retrying. */
    private static boolean backoff(int attempt) {
        try {
            Thread.sleep(BASE_BACKOFF_MILLIS * (1L << (attempt - 1)));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static class ReplyNotAcceptedException extends RuntimeException {
        public ReplyNotAcceptedException(TaskStatusChanged reply, Throwable cause) {
            super("The broker did not accept the " + reply.status() + " reply for task "
                    + reply.taskExecutionId() + " after " + ATTEMPTS
                    + " attempts; the task will be redelivered", cause);
        }
    }

    private WorkerReply() {
    }
}
