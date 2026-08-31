package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Outgoing port for engine observability metrics.
 *
 * All methods are no-ops by default so the engine runs unchanged when no metrics
 * backend is configured. When Micrometer and a {@code MeterRegistry} bean are
 * present, {@code WorkflowMetricsAutoConfiguration} provides an implementation
 * that publishes these signals as Micrometer meters.
 *
 * Instrumented at the use-case layer, so the same metrics are emitted in all
 * deployment modes (embedded+memory, embedded+jpa, kafka+jpa).
 */
public interface WorkflowMetrics {

    WorkflowMetrics NOOP = new WorkflowMetrics() {};

    default void processStarted(String workflowDefinitionId) {}

    default void processCompleted(String workflowDefinitionId, Duration duration) {}

    default void processErrored(String workflowDefinitionId, Duration duration) {}

    default void processCancelled(String workflowDefinitionId, Duration duration) {}

    default void stepExecutionFinished(String workflowDefinitionId, StepExecutionStatus outcome, Duration duration) {}

    default void retryPerformed(String workflowDefinitionId, RetryTrigger trigger) {}

    default void compensationTriggered(String workflowDefinitionId) {}

    /**
     * A saga rollback could not complete: a compensation step itself failed, leaving the process
     * partially rolled back (status {@code COMPENSATION_FAILED}). The one compensation metric to
     * alert on — a non-zero rate is money left in an inconsistent state that a human must resolve.
     */
    default void compensationFailed(String workflowDefinitionId) {}

    /**
     * A write lost an optimistic-locking race: two writers touched the same process.
     *
     * <p>Worth watching rather than merely logging. Events are keyed by process and a consumer
     * group gives each partition to one consumer, so in steady state this must be flat at zero;
     * the only expected source is the brief window of a rebalance, when the outgoing pod may
     * still be finishing a record the incoming one now owns. A non-zero rate outside rebalances
     * means something is reaching a process from outside its partition — which is exactly what
     * has to be true before the pessimistic lock can go.
     */
    default void concurrentWriteRejected(String processId) {}

    /**
     * An event was parked because the engine will never be able to process it.
     *
     * <p>The one counter here that should trigger someone looking: a retry is the engine coping,
     * a dead letter is the engine giving up on a specific event and saying so.
     */
    default void eventDeadLettered(String source) {}

    /**
     * How many live steps are waiting on a worker with no deadline that could ever fire.
     *
     * <p>The one gauge to alert on. Everything else here counts things happening; this counts
     * things that have stopped happening and that nothing in the engine will notice — a step
     * whose dispatch or whose worker reply was lost, on a step that declares no timeout, waits
     * forever and is invisible to the deadline scan by construction. Any sustained non-zero
     * value is work that will never finish.
     *
     * <p>Steps that wait without a deadline by design — human tasks, message catches, child
     * processes — are not in this number, or "work that will never finish" would describe every
     * approval anyone has ever been asked for. The count is cluster-wide and reported by every
     * pod: alert on the maximum across replicas, not the sum.
     */
    default void stalledStepsObserved(long count) {}

    /**
     * Processes that are RUNNING with nothing left to run and no clock anywhere — see
     * {@code ProcessRepository#findStalled}.
     *
     * <p>Distinct from {@link #stalledStepsObserved} and not a subset of it: that one counts live
     * steps a worker owes an answer for, and a process counted here has no live step at all.
     * A deployment can have either without the other.
     *
     * <p>Cluster-wide, like the step figure: every pod reports the same number because both count
     * rows in a shared table. Alert on the maximum across replicas, never the sum.
     */
    default void stalledProcessesObserved(long count) {}

    /**
     * How long a message sat in the outbox between being committed and being claimed by a relay.
     *
     * <p>The engine's throughput is (processes advancing at once) ÷ (latency per step), and both
     * terms are waiting rather than working — which is why a cluster at its ceiling shows every pod
     * idle. This is the first of the three waits, and the only one that can be attributed without
     * a distributed trace.
     *
     * <p><b>Read the distribution, not the mean.</b> {@link io.mateu.workflow.infra.out.async.OutboxSignal}
     * is a semaphore inside one JVM: it wakes the relay of the pod that wrote the row, and the poll
     * interval is the fallback for every row written by some other pod, which this pod has no way
     * of hearing about. So a healthy multi-pod cluster is expected to be <em>bimodal</em> — a fast
     * mode of locally-signalled rows and a slow one gathered around half the poll interval. If the
     * slow mode carries most of the messages, the fix is a cross-pod wakeup, and no amount of CPU
     * or disk will move it.
     *
     * <p>Its count is also the write-amplification numerator: divide by processes started to get
     * relayed events per process instance, which is what converts an events/s ceiling into PI/s.
     */
    default void outboxMessageRelayed(Duration ageAtClaim) {}

    /**
     * The messages in one relay batch, and the time spent inside their sends.
     *
     * <p>The number to look at before changing anything in the relay. Sends are synchronous (see
     * {@code SynchronousProducerDefaults} — an asynchronous send cannot report a refusal, and the
     * outbox contract depends on knowing), so with the relay's concurrency at its default of 1 this
     * duration is messages × broker-ack latency, paid in series. If it dominates the draining time
     * below, that serialization is the ceiling and no resource will move it.
     *
     * <p>It is wall-clock across the batch, not the sum of its sends, so it stays the right number
     * once the batch runs its partition keys concurrently: the gap that opens between this and
     * messages × ack latency is exactly what the barrier bought.
     */
    default void outboxBatchDelivered(int messages, Duration inDeliver) {}

    /**
     * One turn of the relay loop: time spent draining, then time spent waiting for work.
     *
     * <p>Together these give the relay's duty cycle — {@code draining / (draining + waiting)},
     * computable as a ratio of the two rates. A duty cycle near 1 means the single relay thread
     * never gets to wait, i.e. it <em>is</em> the ceiling, and every knob outside it (poll
     * interval, partitions, consumer concurrency, faster disks) is aimed at the wrong thing.
     *
     * <p>Deliberately two durations from one call site rather than a gauge: a gauge would need a
     * window, and a lifetime average of a pod that has been up for days answers no question anyone
     * asked. Two timers let the window be chosen when the question is.
     */
    default void outboxRelayCycle(Duration draining, Duration waiting) {}

    /**
     * A relay pass that claimed rows and settled none of them — the broker is refusing.
     *
     * <p>This exists because the fix for the hot loop took away the signal that used to show an
     * outage: an error line per message per pass, at the cadence of writes. Backing off removes the
     * flood, and without this it would also remove the evidence. A rate above zero here means
     * messages are sitting in the outbox undelivered, which is the alertable condition; the growing
     * gap between passes means it stays true for a while after the broker recovers.
     */
    default void outboxRelayStalled() {}

    enum RetryTrigger { AUTO, MANUAL }

    /**
     * Wall-clock duration of a process, best effort: from started (falling back to
     * created) until finished (falling back to now). Null when the process has no
     * usable start timestamp.
     */
    static Duration durationOf(Process process) {
        var start = process.getStarted() != null ? process.getStarted() : process.getCreated();
        if (start == null) {
            return null;
        }
        var end = process.getFinished() != null ? process.getFinished() : LocalDateTime.now();
        return Duration.between(start, end);
    }
}
