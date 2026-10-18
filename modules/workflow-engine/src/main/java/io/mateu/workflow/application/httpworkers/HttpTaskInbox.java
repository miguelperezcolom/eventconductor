package io.mateu.workflow.application.httpworkers;

import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * The engine's side of the HTTP worker queue: tasks dispatched and cancellations, as the routing
 * publisher hands them over. Depends on nothing of the worker API, so the dispatch path can reach it
 * without a cycle through the engine (the worker API replies <em>into</em> the engine).
 *
 * <p>In JPA mode the dispatch runs inside the outbox relay's transaction, and the queue row joins
 * it: the task is queued if and only if the dispatch commits. The long-poll dispatcher is woken
 * after that commit, never before — a waiter woken early would look for a row it cannot see yet.
 */
@Slf4j
public class HttpTaskInbox {

    private final HttpTaskQueue queue;
    private final HttpTaskSignal signal;
    private final HttpWorkerMetrics metrics;
    private final Clock clock;

    public HttpTaskInbox(HttpTaskQueue queue, HttpTaskSignal signal, HttpWorkerMetrics metrics, Clock clock) {
        this.queue = queue;
        this.signal = signal;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Queues a task the engine dispatched on {@code topic}. */
    public void dispatched(TaskExecutionRequested request, String topic) {
        var stored = queue.offer(HttpTask.queued(request.taskExecutionId(), request.processId(),
                request.workflowDefinitionId(), request.stepId(), request.taskId(),
                DownstreamEventPublisher.destinationFor(topic), request.variables(), request.attempt(),
                LocalDateTime.now(clock)));
        if (!stored) {
            log.debug("Task {} attempt {} is already queued for HTTP workers; duplicate dispatch ignored",
                    request.taskExecutionId(), request.attempt());
            return;
        }
        metrics.enqueued(DownstreamEventPublisher.destinationFor(topic));
        raiseAfterCommit();
    }

    /**
     * The engine cancelled the task.
     *
     * @return whether the queue held it
     */
    public boolean cancelled(String taskExecutionId) {
        var outcome = queue.cancel(taskExecutionId, LocalDateTime.now(clock));
        switch (outcome) {
            case REMOVED -> metrics.cancelled("removed");
            case FLAGGED -> metrics.cancelled("flagged");
            default -> {
                return false;
            }
        }
        return true;
    }

    private void raiseAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    signal.raise();
                }
            });
            return;
        }
        signal.raise();
    }
}
