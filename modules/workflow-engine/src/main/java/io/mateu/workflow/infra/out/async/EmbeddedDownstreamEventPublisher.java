package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.application.out.EmbeddedTaskExecutor;
import io.mateu.workflow.application.services.EventFailures;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hands a task to the embedded worker.
 *
 * <p>Two things happen here that decide whether a blocking or throwing worker stops one step or
 * the whole engine.
 *
 * <h2>Where the worker runs</h2>
 *
 * <p>By default, on the calling thread. In {@code memory} persistence that thread belongs to
 * whoever moved the process (the request thread, the timeout scheduler); in {@code jpa} it is
 * {@code embedded-outbox-relay}, the single thread that drains the outbox — which is to say the
 * only thread advancing <em>every</em> process in the JVM. A worker that blocks there stops all
 * of them: no step-over runs, no process created afterwards ever starts, and its steps sit in
 * {@code CREATED} looking as if their preconditions were never met.
 *
 * <p>Set {@code workflow.embedded.worker-threads} above zero and the task is handed to a bounded
 * pool instead, leaving the caller free. What that costs, and why it is not the default:
 *
 * <ul>
 *   <li><b>Delivery stops meaning completion.</b> Inline, the outbox row is marked {@code Sent}
 *       only once the worker returned, so a crash mid-task redelivers it. Through a pool,
 *       {@code Sent} means handed off — the same thing it means in {@code kafka} mode, where
 *       publishing reaches a broker and not a finished task. A task lost to a crash after the
 *       handoff is recovered by the step's own {@code timeout}, so give ACTION steps one (or set
 *       {@code workflow.default-step-timeout-ms}) before turning this on.</li>
 *   <li><b>Redelivery stops retrying the worker.</b> Inline, a retryable failure propagates and
 *       the relay re-dispatches next cycle. Off-thread there is no one left to propagate to, so
 *       the step is failed instead and the normal retry/compensation pipeline takes over.</li>
 *   <li><b>Tasks of one process can overlap.</b> Reporting stays serialised by the process lock,
 *       but a worker written for embedded/memory can no longer assume it is called one at a
 *       time.</li>
 * </ul>
 *
 * <p>A full pool rejects rather than queues without bound, and the rejection is classified
 * retryable ({@link EventFailures}), so the outbox holds the message and offers it again — the
 * backpressure ends up where there is somewhere to put it.
 *
 * <h2>What happens when the worker throws</h2>
 *
 * <p>The step is failed, in both modes. It used to be that an exception escaping a worker left
 * the {@code StepExecution} exactly as it was — {@code PENDING}, with the outbox row parked as
 * {@code Error} — so a step whose worker threw waited for a reply that was never coming, and
 * without a {@code timeout} nothing would ever look at it again. An unhandled throw is not a
 * reported failure, but it is a failure, and the engine now records it as one so retries and
 * compensation engage.
 *
 * <p>The exception to that is a retryable failure on the calling thread: the database being
 * unreachable says nothing about the step, and the message is worth redelivering rather than
 * turning into a failed step, so it is rethrown as before.
 */
@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "embedded", matchIfMissing = true)
@Slf4j
public class EmbeddedDownstreamEventPublisher implements DownstreamEventPublisher {

    private final EmbeddedTaskExecutor embeddedTaskExecutor;
    private final UpdateStepExecutionUseCase updateStepExecution;
    private final int workerThreads;
    private final int queueCapacity;
    private final long shutdownGraceMillis;

    /** Null when dispatch is inline, which is the default. */
    private volatile ThreadPoolExecutor executor;

    public EmbeddedDownstreamEventPublisher(
            EmbeddedTaskExecutor embeddedTaskExecutor,
            // @Lazy because the host's worker is itself built with the use case this publisher
            // needs, and asking for both eagerly closes the circle through the application's own
            // beans rather than through any of the engine's.
            @Lazy UpdateStepExecutionUseCase updateStepExecution,
            @Value("${workflow.embedded.worker-threads:0}") int workerThreads,
            @Value("${workflow.embedded.worker-queue-capacity:1000}") int queueCapacity,
            @Value("${workflow.embedded.worker-shutdown-grace-ms:10000}") long shutdownGraceMillis) {
        this.embeddedTaskExecutor = embeddedTaskExecutor;
        this.updateStepExecution = updateStepExecution;
        this.workerThreads = workerThreads;
        this.queueCapacity = queueCapacity;
        this.shutdownGraceMillis = shutdownGraceMillis;
    }

    @PostConstruct
    void start() {
        if (workerThreads <= 0) {
            log.debug("Embedded task dispatch is inline: a worker that blocks holds up the thread "
                    + "that dispatched it. Set workflow.embedded.worker-threads to hand tasks to a "
                    + "pool instead.");
            return;
        }
        var threadNumber = new AtomicInteger();
        executor = new ThreadPoolExecutor(workerThreads, workerThreads,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(Math.max(1, queueCapacity)),
                runnable -> {
                    var thread = new Thread(runnable, "embedded-worker-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        log.info("Embedded task dispatch runs on {} worker thread(s), queue capacity {}",
                workerThreads, Math.max(1, queueCapacity));
    }

    /**
     * Lets in-flight tasks finish before the context goes away. A worker killed mid-task leaves a
     * step it will never report on, which is exactly the state this class exists to stop producing.
     */
    @PreDestroy
    void stop() {
        var pool = executor;
        if (pool == null) {
            return;
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(shutdownGraceMillis, TimeUnit.MILLISECONDS)) {
                log.warn("Embedded workers did not finish within {}ms; {} task(s) abandoned",
                        shutdownGraceMillis, pool.shutdownNow().size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    @Override
    public void publish(DomainEvent event) {
        // Cancellations (and any future downstream event type) also flow through here;
        // casting blindly would throw ClassCastException on TaskCancellationRequested.
        // Embedded executors run in this JVM, so there is no remote in-flight work to cancel.
        if (!(event instanceof TaskExecutionRequested request)) {
            log.debug("Ignoring downstream event {} in embedded mode", event.getClass().getSimpleName());
            return;
        }
        var pool = executor;
        if (pool == null) {
            executeInline(request);
            return;
        }
        // A rejection here propagates: it is classified retryable, so the message stays in the
        // outbox and is offered again once the pool has room.
        pool.execute(() -> executeOffTheCallingThread(request));
    }

    private void executeInline(TaskExecutionRequested request) {
        try {
            embeddedTaskExecutor.execute(request);
        } catch (Throwable failure) {
            if (EventFailures.isRetryable(failure)) {
                // The environment, not the step. Let it go up so the message is redelivered.
                throw failure;
            }
            failStep(request, failure);
        }
    }

    private void executeOffTheCallingThread(TaskExecutionRequested request) {
        try {
            embeddedTaskExecutor.execute(request);
        } catch (Throwable failure) {
            // Nothing above this frame belongs to the dispatch any more — the message was
            // delivered the moment it was queued — so every failure has to become the step's.
            failStep(request, failure);
        }
    }

    private void failStep(TaskExecutionRequested request, Throwable failure) {
        log.error("Embedded worker threw while executing step '{}' of process {}; failing the step "
                        + "execution {}", request.stepId(), request.processId(),
                request.taskExecutionId(), failure);
        try {
            updateStepExecution.handle(new UpdateStepExecutionCommand(
                    request.taskExecutionId(),
                    List.of(),
                    describe(failure),
                    StepExecutionStatus.ERROR));
        } catch (Exception reportingFailure) {
            // Best effort by construction: if this cannot be written the step keeps waiting, and
            // only its timeout will free it. Said out loud rather than swallowed.
            log.error("Could not record the failure of step execution {}; it stays in flight until "
                    + "its timeout", request.taskExecutionId(), reportingFailure);
        }
    }

    /**
     * What goes in the process log, and therefore what an operator reads in the Errors tab and on
     * the graph's hover card. The root cause is included because the outer exception is often the
     * one that says least: {@code ResourceAccessException: I/O error on POST …: null} is a
     * {@code ConnectException} with the useful half removed.
     */
    private String describe(Throwable failure) {
        var message = new StringBuilder("Worker threw ").append(failure.getClass().getSimpleName());
        if (failure.getMessage() != null && !failure.getMessage().isBlank()) {
            message.append(": ").append(failure.getMessage());
        }
        var root = rootCauseOf(failure);
        if (root != failure) {
            message.append(" (caused by ").append(root.getClass().getSimpleName());
            if (root.getMessage() != null && !root.getMessage().isBlank()) {
                message.append(": ").append(root.getMessage());
            }
            message.append(')');
        }
        return message.toString();
    }

    /** Bounded, because a cause chain can be circular and this runs on a failure path. */
    private Throwable rootCauseOf(Throwable failure) {
        var cause = failure;
        for (int depth = 0; depth < 20 && cause.getCause() != null && cause.getCause() != cause; depth++) {
            cause = cause.getCause();
        }
        return cause;
    }
}
