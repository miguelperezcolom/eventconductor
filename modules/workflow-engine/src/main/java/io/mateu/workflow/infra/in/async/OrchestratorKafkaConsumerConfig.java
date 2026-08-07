package io.mateu.workflow.infra.in.async;

import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventCommand;
import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventUseCase;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Configuration
@ConditionalOnProperty(name = "workflow.mode", havingValue = "kafka")
@RequiredArgsConstructor
@Slf4j
public class OrchestratorKafkaConsumerConfig {

    final ProcessDomainEventUseCase processDomainEventUseCase;
    final ProcessUpstreamEventUseCase processUpstreamEventUseCase;
    final TransactionTemplate transactionTemplate;
    final io.mateu.workflow.application.out.DeadLetterPublisher deadLetterPublisher;

    /**
     * Try a whole slice of a poll batch as one transaction before falling back to one per process.
     *
     * <p>Off by default. It changes nothing about what may be committed — see
     * {@link #inOneTransaction} — but it is a change to the hot path of the engine's durability, and
     * the number it moves is only visible on a cluster.
     */
    @org.springframework.beans.factory.annotation.Value("${workflow.consumer.batch-transaction:false}")
    boolean batchTransaction;

    /**
     * How many processes one transaction may cover. Bounds how long it holds its rows and how much
     * work a single failure throws away, which is the cost the fast path trades against fsyncs.
     */
    @org.springframework.beans.factory.annotation.Value("${workflow.consumer.batch-transaction-max-processes:32}")
    int maxProcessesPerTransaction;

    /**
     * Slices to run per-process after the fast path fails, before trying it again.
     *
     * <p>Without this a partition carrying a permanently poisoned event would fail the fast path on
     * every batch for good, paying for the attempt every time and never gaining anything. A
     * conflict is rare and transient; a poison event is neither.
     */
    @org.springframework.beans.factory.annotation.Value("${workflow.consumer.batch-transaction-backoff:20}")
    int backoffSlices;

    private final java.util.concurrent.atomic.AtomicInteger fastPathPausedFor =
            new java.util.concurrent.atomic.AtomicInteger();

    @Bean
    public Consumer<Message<List<DomainEvent>>> consumeOutbox() {
        return message -> perProcess(message.getPayload(), event ->
                processDomainEventUseCase.handle(new ProcessDomainEventCommand(event)), "outbox");
    }

    @Bean
    public Consumer<List<DomainEvent>> consumeUpstream() {
        return events -> perProcess(events, event ->
                processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(event)), "upstream");
    }

    /**
     * The shared cross-shard messages channel. Every shard binds this to the one {@code messages}
     * topic under its own consumer group, so each receives every {@link io.mateu.workflow.dtos.events.integration.MessageReceived}
     * and correlates it against its own WAIT_FOR_MESSAGE steps; the shard that owns the waiter resumes
     * it, the rest match nothing and drop it (fail-closed). Correlation is the same upstream path — only
     * the topic the message arrived on differs. Bound only where {@code messages} is configured (sharded
     * deployments); an unlisted function bean is inert, so single-cluster deployments are unaffected.
     */
    @Bean
    public Consumer<List<DomainEvent>> consumeMessages() {
        return events -> perProcess(events, event ->
                processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(event)), "messages");
    }

    /**
     * Commits a poll batch as one transaction per process rather than one per event — a busy
     * batch carries several events for the same process, and collapsing those into a single
     * commit is where the saving is.
     *
     * <p>Per process, and not per batch, on purpose. A batch-wide transaction sounds better and
     * is a trap: one failure inside it — an optimistic conflict, which is exactly what a
     * rebalance produces — marks the whole transaction rollback-only, so every other event that
     * believed it had committed is rolled back with it and redelivered, and the pods least able
     * to cope get the largest batches to redo. Per process the blast radius is one process, which
     * is already the unit of redelivery.
     *
     * <p>Events of a process stay in the order they arrived: Kafka orders them within a
     * partition, and the grouping preserves encounter order. Events with no process of their own
     * each get their own transaction.
     *
     * <p>With {@code workflow.consumer.batch-transaction} on, a slice of processes is first tried
     * as a single transaction — one fsync instead of one per process — and falls back to this exact
     * behaviour if that attempt does not commit. See {@link #inOneTransaction}: the fast path never
     * commits part of a slice, so the fallback always begins where this would have begun.
     */
    private void perProcess(List<DomainEvent> events, Consumer<DomainEvent> handle) {
        perProcess(events, handle, "unknown");
    }

    private void perProcess(List<DomainEvent> events, Consumer<DomainEvent> handle, String source) {
        var byProcess = new LinkedHashMap<String, List<DomainEvent>>();
        for (var event : events) {
            var key = event.partitionKey() == null
                    // No process: give it a group of its own so it neither drags others down nor
                    // is dragged down by them.
                    ? "\u0000unkeyed-" + System.identityHashCode(event)
                    : event.partitionKey();
            byProcess.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(event);
        }
        var groups = new java.util.ArrayList<>(byProcess.values());
        for (var from = 0; from < groups.size(); from += maxProcessesPerTransaction) {
            var slice = groups.subList(from, Math.min(from + maxProcessesPerTransaction, groups.size()));
            if (!inOneTransaction(slice, handle)) {
                onePerProcess(slice, handle, source);
            }
        }
    }

    /**
     * Tries a whole slice as a single transaction, and says whether it committed.
     *
     * <p>Every process in the slice is one step of one process, and every step is a commit, so a
     * batch of thirty processes costs thirty fsyncs on the database that gates the entire engine.
     * Running them together costs one. The reason it was one transaction per process is real and
     * unchanged — a single optimistic conflict marks a shared transaction rollback-only and takes
     * every other process down with it — so this does not try to survive a failure. It has exactly
     * two outcomes: the slice commits, or nothing in it does.
     *
     * <p>That is what makes it safe rather than clever. When it rolls back, the database is in the
     * state it was in before, so {@link #onePerProcess} starts from the same place it would have
     * started from had this never run — and {@code onePerProcess} is the code that shipped, with
     * its retryable-versus-poison decision untouched. There is no third outcome to reason about,
     * no partially-committed slice, and no new failure mode: only an attempt that either wins
     * whole or leaves no trace.
     *
     * <p>Deliberately catches everything and classifies nothing. Deciding here whether a failure is
     * retryable or poisoned would duplicate the one place that decision is made; failing and
     * deferring keeps it in one place.
     *
     * <p>Note what this does <em>not</em> do: it does not reduce the commits a process costs over
     * its life — that is still one per transition. It shares each transition's fsync with the other
     * processes in the same slice, so the saving scales with how many distinct processes a poll
     * batch carries, which is largest exactly when the engine is busiest.
     *
     * <h2>The one thing a rollback does not undo</h2>
     *
     * <p><b>Dispatching a task to a worker is a Kafka send, not a database write.</b>
     * {@code StartStepExecutionUseCase} publishes downstream through {@code DownstreamEventPublisher}
     * inside the same transaction, and no rollback recalls a published record. So a slice that rolls
     * back may already have dispatched tasks for the processes it got through, and the fallback will
     * dispatch them again.
     *
     * <p>Nothing is lost by that and nothing is new about it: the identical window exists today for
     * a single process whose transaction fails after its dispatch, and the retryable path already
     * redelivers whole batches over slices that committed — which is why the engine requires
     * handlers and workers to be idempotent, and says so where it relies on it.
     *
     * <p>What changes is the <em>blast radius</em>. Today one failure can duplicate one process's
     * dispatch; here it can duplicate up to a slice's worth. That is the real cost of this switch,
     * it is bounded by {@code batch-transaction-max-processes}, and it is why the default is off:
     * turning it on is a statement that worker idempotency holds at slice granularity, not merely
     * at process granularity.
     */
    private boolean inOneTransaction(List<List<DomainEvent>> slice, Consumer<DomainEvent> handle) {
        if (!batchTransaction || slice.size() < 2) {
            // A single process is already a single transaction, so there is nothing to win and
            // nothing to spend: it must not burn down the back-off either.
            return false;
        }
        if (fastPathPausedFor.getAndUpdate(paused -> paused > 0 ? paused - 1 : 0) > 0) {
            return false;
        }
        try {
            transactionTemplate.executeWithoutResult(status ->
                    slice.forEach(group -> group.forEach(event -> {
                        log.debug("Processing {}", event);
                        handle.accept(event);
                    })));
            return true;
        } catch (Exception e) {
            log.debug("Batch transaction over {} processes rolled back, falling back to one "
                    + "transaction per process", slice.size(), e);
            fastPathPausedFor.set(backoffSlices);
            return false;
        }
    }

    /** The original path, unchanged: one transaction per process, and it decides what a failure means. */
    private void onePerProcess(List<List<DomainEvent>> slice, Consumer<DomainEvent> handle, String source) {
        for (List<DomainEvent> group : slice) {
            try {
                transactionTemplate.executeWithoutResult(status ->
                        group.forEach(event -> {
                            log.debug("Processing {}", event);
                            handle.accept(event);
                        }));
            } catch (Exception e) {
                if (io.mateu.workflow.application.services.EventFailures.isRetryable(e)) {
                    // The environment, not the event: let it out so the binder redelivers the
                    // batch rather than committing over work that never happened. Handlers are
                    // idempotent, so repeating the slices that did commit is harmless.
                    throw e;
                }
                // This slice will fail the same way forever. Park its events where someone can
                // look at them and replay them, and let the rest of the batch through — the
                // alternative is a poison event stalling a partition for good.
                group.forEach(event -> deadLetterPublisher.park(event, e, source));
            }
        }
    }

}
