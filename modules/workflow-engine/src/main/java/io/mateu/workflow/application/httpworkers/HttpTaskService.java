package io.mateu.workflow.application.httpworkers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.StepsInjected;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.workflow.worker.FailureMarkers;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static io.mateu.workflow.application.httpworkers.HttpTaskException.Reason.CANCELLED;
import static io.mateu.workflow.application.httpworkers.HttpTaskException.Reason.INVALID;
import static io.mateu.workflow.application.httpworkers.HttpTaskException.Reason.LEASE_LOST;
import static io.mateu.workflow.application.httpworkers.HttpTaskException.Reason.NOT_FOUND;
import static io.mateu.workflow.application.httpworkers.HttpTaskException.Reason.STALE_ATTEMPT;
import static io.mateu.workflow.application.httpworkers.HttpTaskException.Reason.UNAVAILABLE;

/**
 * The worker side of the HTTP queue: leasing tasks, and turning a worker's answer into the very
 * events a Kafka worker sends on {@code upstream} — {@link TaskStatusChanged}, {@link TaskLogEmitted}
 * for a failure's reason, {@link StepsInjected} for a DYNAMIC step — published through the engine's
 * own {@link UpstreamEventPublisher}. The engine cannot tell an HTTP worker from a Kafka one, and in
 * kafka mode the reply is keyed by process, so it reaches the node that owns the process whichever
 * node the worker called.
 *
 * <p><b>Late replies.</b> A reply must echo the {@code attempt} it was handed. One for a dispatch the
 * engine has replaced (the step was retried, the process restarted) is refused with
 * {@link HttpTaskException.Reason#STALE_ATTEMPT} and never reaches the engine. Within one dispatch the
 * first reply wins and removes the task; a second one — a worker whose lease expired while it was
 * still working, and whose task was re-leased — finds it gone.
 *
 * <p><b>Time.</b> Leases run on {@code clock} — the wall clock (UTC) in production — and not on
 * {@code EngineClock}: like the outbox's delivery claims and leases, which {@code EngineClock} names
 * as not business time, a lease measures a worker thread and a socket waiting in real time. Skipping
 * business time in a test must not expire every lease a live worker holds, and a lease never feeds a
 * process or step timestamp. The {@code deadline} a worker is handed is therefore an instant, not
 * engine time.
 *
 * <p><b>Order.</b> The reply is published first and the task removed after, so a reply the engine
 * would not take leaves the task where it was (503, the worker retries) rather than lost.
 */
@Slf4j
public class HttpTaskService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpTaskQueue queue;
    private final UpstreamEventPublisher upstream;
    private final HttpWorkerMetrics metrics;
    private final Clock clock;

    public HttpTaskService(HttpTaskQueue queue, UpstreamEventPublisher upstream, HttpWorkerMetrics metrics,
                           Clock clock) {
        this.queue = queue;
        this.upstream = upstream;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** The lease clock's now (wall clock, UTC in production). */
    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** Leases up to {@code max} tasks of {@code topics} to {@code workerId} for {@code lease}. */
    public List<HttpTask> claim(String workerId, Collection<String> topics, int max, Duration lease) {
        if (topics == null || topics.isEmpty() || max <= 0) {
            return List.of();
        }
        var now = now();
        var tasks = queue.claim(topics, max, workerId, now, now.plus(lease));
        for (var task : tasks) {
            metrics.activated(task.topic(), task.deliveries() > 1);
            if (task.deliveries() > 1) {
                log.info("Task {} (step '{}' of process {}) redelivered to worker {}: delivery {}",
                        task.id(), task.stepId(), task.processId(), workerId, task.deliveries());
            }
        }
        return tasks;
    }

    /** Gives back leases an activation could not hand to anybody. */
    public void release(List<HttpTask> tasks) {
        for (var task : tasks) {
            try {
                queue.release(task.id(), task.leaseToken());
            } catch (RuntimeException e) {
                log.warn("Could not give back the lease of task {}; it is redelivered when it expires", task.id(), e);
            }
        }
    }

    /** The worker finished the task: its output variables go to the engine. */
    public void complete(String id, Integer attempt, Map<String, Object> variables) {
        var task = answerable(id, attempt);
        publish(new TaskStatusChanged(task.id(), TaskStatus.COMPLETED, toVariables(variables), task.processId(),
                false, task.attempt()));
        queue.remove(task.id(), task.attempt());
        metrics.reply("completed");
    }

    /**
     * The worker failed the task. The reason ({@code code: message}) is logged on the process; a
     * failure that is not {@code retryable} fails the step at once instead of spending its retries.
     */
    public void fail(String id, Integer attempt, String code, String message, boolean retryable) {
        var task = answerable(id, attempt);
        var reason = reasonOf(code, message);
        if (!retryable) {
            reason = FailureMarkers.nonRetryable(reason);
        }
        publish(new TaskLogEmitted(task.id(), MessageType.Error, reason));
        publish(new TaskStatusChanged(task.id(), TaskStatus.ERROR, List.of(), task.processId(), !retryable,
                task.attempt()));
        queue.remove(task.id(), task.attempt());
        metrics.reply("failed");
    }

    /**
     * Injects steps into the process of a DYNAMIC step's task; the task stays the worker's, to be
     * completed afterwards. The engine validates the batch and fails the step if it is refused.
     */
    public void inject(String id, Integer attempt, String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            throw new HttpTaskException(INVALID, "steps is required");
        }
        var task = answerable(id, attempt);
        publish(new StepsInjected(task.id(), task.processId(), stepsJson));
        metrics.reply("injected");
    }

    /**
     * Extends the lease, or says the task was cancelled (the row is then dropped: the worker knows).
     *
     * @param leaseToken the lease the worker was handed
     * @param extension  from now; capped by the caller
     */
    public Heartbeat heartbeat(String id, String leaseToken, Duration extension) {
        if (leaseToken == null || leaseToken.isBlank()) {
            throw new HttpTaskException(INVALID, "leaseToken is required");
        }
        var task = queue.find(id).orElseThrow(() -> gone(id));
        if (task.cancelled()) {
            return cancelledHeartbeat(task);
        }
        if (!leaseToken.equals(task.leaseToken())) {
            throw new HttpTaskException(LEASE_LOST, "The lease of task " + id + " expired and was given to another worker.");
        }
        var until = now().plus(extension);
        if (queue.extend(id, leaseToken, until)) {
            return new Heartbeat(false, until);
        }
        // Lost a race: cancelled or re-leased between the read and the update.
        var current = queue.find(id).orElseThrow(() -> gone(id));
        if (current.cancelled()) {
            return cancelledHeartbeat(current);
        }
        throw new HttpTaskException(LEASE_LOST, "The lease of task " + id + " expired and was given to another worker.");
    }

    private Heartbeat cancelledHeartbeat(HttpTask task) {
        queue.remove(task.id(), task.attempt());
        metrics.cancelled("delivered");
        return new Heartbeat(true, null);
    }

    public record Heartbeat(boolean cancelled, LocalDateTime leaseUntil) {
    }

    /** The queued task a reply is for, if the reply is still wanted. */
    private HttpTask answerable(String id, Integer attempt) {
        var task = queue.find(id).orElse(null);
        if (task == null) {
            metrics.reply("gone");
            throw gone(id);
        }
        if (task.attempt() != null) {
            if (attempt == null) {
                throw new HttpTaskException(INVALID, "attempt is required: echo the attempt the task was activated with");
            }
            if (!Objects.equals(attempt, task.attempt())) {
                metrics.reply("stale");
                throw new HttpTaskException(STALE_ATTEMPT, "Task " + id + " is on attempt " + task.attempt()
                        + "; a reply for attempt " + attempt + " is no longer wanted.");
            }
        }
        if (task.cancelled()) {
            queue.remove(task.id(), task.attempt());
            metrics.reply("cancelled");
            throw new HttpTaskException(CANCELLED, "Task " + id + " was cancelled by the engine.");
        }
        return task;
    }

    private void publish(io.mateu.workflow.ddd.DomainEvent event) {
        try {
            upstream.publish(event);
        } catch (RuntimeException e) {
            log.warn("The engine did not take a worker reply ({}): {}", event.getClass().getSimpleName(), e.toString());
            throw new HttpTaskException(UNAVAILABLE, "The engine could not take the reply right now; retry it.", e);
        }
    }

    private static HttpTaskException gone(String id) {
        return new HttpTaskException(NOT_FOUND, "No task " + id + " is waiting for a reply: it was answered already, "
                + "cancelled, or never served over HTTP.");
    }

    static String reasonOf(String code, String message) {
        var hasCode = code != null && !code.isBlank();
        var hasMessage = message != null && !message.isBlank();
        if (hasCode && hasMessage && !message.equals(code)) {
            return code + ": " + message;
        }
        if (hasCode) {
            return code;
        }
        return hasMessage ? message : "The worker reported a failure without a reason";
    }

    /** Worker variables in: strings as they are, anything else as its JSON text — as a Java worker's output. */
    static List<Variable> toVariables(Map<String, Object> variables) {
        if (variables == null) {
            return List.of();
        }
        var result = new ArrayList<Variable>(variables.size());
        variables.forEach((name, value) -> result.add(new Variable(name, stringify(value))));
        return result;
    }

    private static String stringify(Object value) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new HttpTaskException(INVALID, "Unreadable variable value: " + e.getMessage());
        }
    }

    /** A fresh, unguessable lease token. */
    public static String newLeaseToken() {
        return UUID.randomUUID().toString();
    }
}
