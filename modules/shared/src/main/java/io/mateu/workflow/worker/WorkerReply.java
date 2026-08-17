package io.mateu.workflow.worker;

import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.StepsInjected;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
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
 * <p>Nobody has to set it: {@link SynchronousProducerDefaults} ships beside this class, in the
 * module every worker already depends on, and contributes it whenever {@code workflow.mode} is
 * {@code kafka}. It used to live in the engine, which meant a module that only replies — the forms
 * engine answering a USER_TASK, the rule runtime answering a RULE step — silently did not get it,
 * and without it this class is decoration.
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
     * Fails the task <b>and says why</b>, which the three-argument overload cannot.
     *
     * <p>A worker in kafka mode had no way to put the reason anywhere the engine reads. Its reply
     * is a {@link TaskStatusChanged}, which carries a status and variables and no message, so the
     * process log said "Task status changed to ERROR" and the reason existed only in the worker's
     * own stdout — if the worker logged it at all. Embedded mode never had the problem: the engine
     * catches the exception on the worker's behalf and fills the {@code log} field of its update
     * command. This closes the same hole on the other side, so a failure reads the same in both
     * modes.
     *
     * <p>The reason goes out <b>first</b>, as a {@link TaskLogEmitted}, and that order is the
     * point. Both sends are on the retry-or-throw path, so a broker that will not take the log
     * line throws before anything has been reported at all — the task is simply redelivered and
     * done again, which is clean. Reporting the failure first and then losing the reason would
     * leave the engine acting on a failure nobody can explain, which is the state this exists to
     * end. A blank or null reason sends nothing extra and behaves exactly like the three-argument
     * overload.
     *
     * <p>Pass something a reader can act on — {@code e.toString()} at minimum, which keeps the
     * exception type. The message is truncated by the engine's log column, so put the useful part
     * first.
     *
     * @param reason why the task failed; null or blank to send no log line
     */
    public static void failed(StreamOperations streamBridge, TaskExecutionRequested task,
                              List<Variable> variables, String reason) {
        if (reason != null && !reason.isBlank()) {
            send(streamBridge, new TaskLogEmitted(
                    task.taskExecutionId(), MessageType.Error, reason));
        }
        failed(streamBridge, task, variables);
    }

    /**
     * Injects new steps into the running process — the DYNAMIC step's one extra move.
     *
     * <p>Only a {@code DYNAMIC} step may inject; the engine rejects the message from any other
     * type. It is <b>add-only</b>: the steps in {@code stepsJson} are materialised alongside the
     * ones already there, never rewriting or removing them. The worker supplies each step
     * <em>with its own preconditions</em> — the relations that make it reachable — because there is
     * no default wiring: an injected step with no precondition is simply unreachable, which is a
     * visible bug in the graph rather than something the engine papers over.
     *
     * <p>The batch is validated engine-side as a whole — unique ids that do not collide with the
     * process's existing steps, every precondition reference resolved, no cycle introduced, within
     * the step budget — and if any of that fails the <b>whole batch is rejected and the DYNAMIC
     * step is failed</b> with the reason (it surfaces on the process's Errors tab). So a rejected
     * injection is a failed step, not a silently dropped one.
     *
     * <p>{@code stepsJson} is a JSON array of step objects in the same schema a workflow definition
     * uses — see the dynamic-workflows guide. Sent through the same synchronous, retry-or-throw
     * path as every other reply, so a broker that will not take it fails the listener and the task
     * is redelivered.
     */
    public static void inject(StreamOperations streamBridge, TaskExecutionRequested task,
                              String stepsJson) {
        send(streamBridge, new StepsInjected(
                task.taskExecutionId(), task.processId(), stepsJson));
    }

    /**
     * Injects the steps, then completes the DYNAMIC step — the common "generate the sub-flow, then
     * finish" reply.
     *
     * <p>Inject first, on purpose: the injected steps must exist before the DYNAMIC step's
     * completion advances the process, or the very steps just added would not yet be there for the
     * engine to consider. Both sends go through the retry-or-throw path; if the injection is
     * refused this throws before the completion is sent, so the task is redelivered rather than the
     * process advanced past an injection that never landed.
     */
    public static void injectAndComplete(StreamOperations streamBridge, TaskExecutionRequested task,
                                         String stepsJson, List<Variable> variables) {
        inject(streamBridge, task, stepsJson);
        completed(streamBridge, task, variables);
    }

    /**
     * Publishes the reply, retrying a refused send, and throws if the broker never takes it.
     *
     * @throws ReplyNotAcceptedException so the caller's listener fails and the offset is not
     *                                   committed. Do not catch it to "keep going" — that is the
     *                                   bug this class exists to prevent.
     */
    public static void send(StreamOperations streamBridge, TaskStatusChanged reply) {
        publish(streamBridge, reply,
                cause -> new ReplyNotAcceptedException(reply, cause));
    }

    /**
     * Publishes a log line against the task, on the same retry-or-throw path as a status reply.
     *
     * <p>The engine records it on the process through {@code RegisterLogMessageUseCase}, keyed by
     * the task execution — so unlike a status change it mutates no aggregate, and it is harmless
     * that a worker's reply arrives unkeyed and is handled by whichever pod receives it.
     */
    public static void send(StreamOperations streamBridge, TaskLogEmitted reply) {
        publish(streamBridge, reply,
                cause -> new ReplyNotAcceptedException(reply, cause));
    }

    /**
     * Publishes a step injection on the same synchronous, retry-or-throw path as a status reply —
     * a refused send is retried and, if the broker still will not take it, throws so the task is
     * redelivered rather than the injection lost.
     */
    public static void send(StreamOperations streamBridge, StepsInjected reply) {
        publish(streamBridge, reply,
                cause -> new ReplyNotAcceptedException(reply, cause));
    }

    /**
     * The shared retry loop behind every reply. Kept generic over the payload because a status
     * change and a step injection are the same delivery problem — publish on {@code upstream},
     * retry a refusal, throw if it never takes — differing only in the exception they raise, which
     * the caller supplies with the last failure as its cause.
     */
    private static void publish(StreamOperations streamBridge, Object reply,
                                java.util.function.Function<RuntimeException, ReplyNotAcceptedException> onExhausted) {
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
        throw onExhausted.apply(lastFailure);
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

        public ReplyNotAcceptedException(TaskLogEmitted reply, Throwable cause) {
            super("The broker did not accept the log line for task "
                    + reply.taskExecutionId() + " after " + ATTEMPTS
                    + " attempts; the task will be redelivered", cause);
        }

        public ReplyNotAcceptedException(StepsInjected reply, Throwable cause) {
            super("The broker did not accept the step injection for task "
                    + reply.taskExecutionId() + " after " + ATTEMPTS
                    + " attempts; the task will be redelivered", cause);
        }
    }

    private WorkerReply() {
    }
}
