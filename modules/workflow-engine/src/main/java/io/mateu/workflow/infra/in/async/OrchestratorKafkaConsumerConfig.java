package io.mateu.workflow.infra.in.async;

import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventCommand;
import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventUseCase;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.TraceContext;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.support.KafkaHeaders;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
     * How many of a poll batch's per-process groups may commit at once, within one consumer thread.
     *
     * <p>Default 1 keeps the original behaviour exactly: groups run one after another on the consumer
     * thread, so in-pod parallelism equals the number of partitions the pod owns (one consumer thread
     * per partition). That ties throughput to partition count — a busy batch on a single partition
     * carries hundreds of <em>independent</em> processes and still commits them one at a time.
     *
     * <p>Above 1, the independent groups of a batch are handed to a shared bounded pool, so a pod can
     * work more processes at once than it has partitions. The groups are independent by construction —
     * distinct processes, distinct rows, and Kafka only orders <em>within</em> a partition, an order
     * this preserves inside each group — so nothing about ownership or ordering changes. Each group
     * still commits in its own transaction, which means the pool takes a JDBC connection per busy
     * thread: keep this at or below the connection pool size (see {@code DB_POOL_SIZE}). Raising it
     * past the partition count is the point — that is where it stops being what partitioning already
     * gave.
     */
    @Value("${workflow.consumer.process-parallelism:1}")
    int processParallelism;

    /** Non-null only when {@link #processParallelism} > 1; shared across all consumer threads so the
     * total number of concurrent process transactions in the pod is bounded by the pool, not multiplied
     * by the number of partitions. */
    private ExecutorService processExecutor;

    @PostConstruct
    void startProcessExecutor() {
        if (processParallelism > 1) {
            processExecutor = new ThreadPoolExecutor(
                    processParallelism, processParallelism, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(),
                    runnable -> {
                        var thread = new Thread(runnable, "process-group-worker");
                        thread.setDaemon(true);
                        return thread;
                    });
            log.info("Per-process parallelism enabled: {} concurrent process transactions per pod "
                    + "(ensure the JDBC pool has at least this many connections)", processParallelism);
        }
    }

    @PreDestroy
    void stopProcessExecutor() {
        if (processExecutor != null) {
            processExecutor.shutdown();
        }
    }

    @Bean
    public Consumer<Message<List<DomainEvent>>> consumeOutbox() {
        return message -> perProcess(message.getPayload(), RecordPositions.of(message), event ->
                processDomainEventUseCase.handle(new ProcessDomainEventCommand(event)), "outbox");
    }

    /**
     * Takes the whole message, not just the payloads, for one reason: the per-record headers. A
     * service that starts a process from inside its own trace may say so with W3C
     * {@code traceparent}/{@code tracestate}/{@code baggage} record headers instead of the
     * {@code traceContext} field, and those headers are only on the message. See
     * {@link #withRecordTraceContexts}.
     */
    @Bean
    public Consumer<Message<List<DomainEvent>>> consumeUpstream() {
        return message -> perProcess(withRecordTraceContexts(message), RecordPositions.of(message), event ->
                processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(event)), "upstream");
    }

    /**
     * The batch's events, each {@link ProcessCreationRequested} that arrived with W3C trace headers
     * but no {@code traceContext} of its own given one from its record's headers.
     *
     * <p>Folded into the payload here, at the edge, rather than carried beside it: the event may be
     * forwarded before it is handled (to the shard that owns its business key) and a field survives
     * that hop where a header on this record would not. The payload wins when both are there — a
     * producer that set the field meant it, while a header may just be whatever span the producer's
     * relay happened to be running in.
     */
    public static List<DomainEvent> withRecordTraceContexts(Message<List<DomainEvent>> message) {
        var events = message.getPayload();
        // Mapped per record when the binder has a header mapper (the default); the raw Kafka headers
        // when it has none.
        var converted = message.getHeaders().get(KafkaHeaders.BATCH_CONVERTED_HEADERS);
        var perRecord = converted != null ? converted : message.getHeaders().get(KafkaHeaders.NATIVE_HEADERS);
        if (!(perRecord instanceof List<?> headers) || headers.size() != events.size()) {
            return events;
        }
        List<DomainEvent> result = null;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof ProcessCreationRequested creation && creation.traceContext() == null) {
                var context = traceContextOf(headers.get(i));
                if (context != null) {
                    if (result == null) {
                        result = new ArrayList<>(events);
                    }
                    result.set(i, creation.withTraceContext(context));
                }
            }
        }
        return result == null ? events : result;
    }

    @SuppressWarnings("unchecked")
    private static TraceContext traceContextOf(Object recordHeaders) {
        if (recordHeaders instanceof java.util.Map<?, ?> mapped) {
            return TraceContext.fromHeaders((java.util.Map<String, ?>) mapped);
        }
        if (recordHeaders instanceof org.apache.kafka.common.header.Headers raw) {
            return TraceContext.fromHeaders(name -> {
                var header = raw.lastHeader(name);
                return header == null ? null : header.value();
            });
        }
        return null;
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
    public Consumer<Message<List<DomainEvent>>> consumeMessages() {
        return message -> perProcess(message.getPayload(), RecordPositions.of(message), event ->
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
     * <p><b>A retryable failure names the record to resume from.</b> It is thrown as a
     * {@link BatchListenerFailedException} pointing at the first record of the earliest group that
     * failed. The container's error handler ({@link KafkaConsumerErrorHandling}) then commits every
     * record before it and redelivers from it, with back-off and without limit — the conflict is
     * retried, not discarded, and the groups that already committed are not redone wholesale. That
     * position is safe because groups run in order of their first record: every record before the
     * failed group's first belongs to a group that has already committed or parked. Records after it
     * that did commit are redone, which the handlers' idempotence makes harmless.
     *
     * <p>With {@link #processParallelism} above 1 the groups commit concurrently; every group runs
     * before the failure is reported, and the earliest failed group is the one named.
     */
    void perProcess(List<DomainEvent> events, RecordPositions positions, Consumer<DomainEvent> handle,
                    String source) {
        var byProcess = new LinkedHashMap<String, Group>();
        for (int i = 0; i < events.size(); i++) {
            var event = events.get(i);
            var key = event.partitionKey() == null
                    // No process: give it a group of its own so it neither drags others down nor
                    // is dragged down by them.
                    ? " unkeyed-" + i
                    : event.partitionKey();
            var index = i;
            byProcess.computeIfAbsent(key, k -> new Group(index)).events.add(event);
        }
        if (processExecutor == null) {
            for (var group : byProcess.values()) {
                try {
                    processGroup(group.events, handle, source);
                } catch (RuntimeException retryable) {
                    throw positions.failedAt(group.firstIndex, retryable, source);
                }
            }
            return;
        }
        processGroupsInParallel(byProcess.values(), positions, handle, source);
    }

    /** One process's slice of a batch, and where in the batch it starts. */
    static final class Group {
        final int firstIndex;
        final List<DomainEvent> events = new ArrayList<>();

        Group(int firstIndex) {
            this.firstIndex = firstIndex;
        }
    }

    /**
     * Runs one process's slice of the batch in its own transaction. A retryable failure is thrown so
     * the caller can have the record redelivered; anything else is parked and swallowed so a single
     * poison process cannot stall the partition for good. A park the broker refuses is thrown too:
     * the record is then the only copy of those events, and it is redelivered rather than committed.
     */
    private void processGroup(List<DomainEvent> group, Consumer<DomainEvent> handle, String source) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                    group.forEach(event -> {
                        log.debug("Processing {}", event);
                        handle.accept(event);
                    }));
        } catch (RuntimeException e) {
            if (io.mateu.workflow.application.services.EventFailures.isRetryable(e)) {
                // The environment, not the event: let it out so the record is redelivered rather
                // than committed over work that never happened. Handlers are idempotent, so
                // repeating the slices that did commit is harmless.
                throw e;
            }
            // This slice will fail the same way forever. Park its events where someone can look at
            // them and replay them, and let the rest of the batch through — the alternative is a
            // poison event stalling a partition for good.
            group.forEach(event -> deadLetterPublisher.park(event, e, source));
        }
    }

    /**
     * Submits every group to the shared pool, waits for all of them, and — only after they have all
     * run — reports the earliest group that failed, so the batch resumes from there. Waiting for the
     * whole batch rather than failing fast means every group that can commit does, and only the
     * failed ones (and whatever follows the earliest of them) are redelivered.
     */
    private void processGroupsInParallel(Iterable<Group> groups, RecordPositions positions,
                                         Consumer<DomainEvent> handle, String source) {
        var futures = new LinkedHashMap<Group, Future<?>>();
        for (var group : groups) {
            futures.put(group, processExecutor.submit(() -> processGroup(group.events, handle, source)));
        }
        Group earliest = null;
        RuntimeException failure = null;
        for (var entry : futures.entrySet()) {
            RuntimeException thrown = null;
            try {
                entry.getValue().get();
            } catch (ExecutionException e) {
                var cause = e.getCause();
                thrown = cause instanceof RuntimeException re ? re : new RuntimeException(cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                thrown = new RuntimeException("Interrupted while processing a poll batch", e);
            }
            if (thrown != null && (earliest == null || entry.getKey().firstIndex < earliest.firstIndex)) {
                earliest = entry.getKey();
                failure = thrown;
            }
        }
        if (earliest != null) {
            throw positions.failedAt(earliest.firstIndex, failure, source);
        }
    }

    /**
     * Where each event of a batch came from, so a failure can name its record — read from the
     * per-record {@code kafka_receivedTopic}/{@code kafka_receivedPartitionId}/{@code kafka_offset}
     * header lists, which the binder keeps aligned with the payload even when it drops a record it
     * could not convert.
     *
     * <p>When the lists are missing or do not line up, the failure names the payload index instead;
     * that is right whenever no record was dropped, and if the error handler cannot find it in the
     * batch it redelivers the whole batch — later, with back-off, never discarded.
     */
    record RecordPositions(List<?> topics, List<?> partitions, List<?> offsets, int size) {

        static final RecordPositions NONE = new RecordPositions(null, null, null, 0);

        static RecordPositions of(Message<? extends List<?>> message) {
            var headers = message.getHeaders();
            return new RecordPositions(
                    headers.get(KafkaHeaders.RECEIVED_TOPIC) instanceof List<?> t ? t : null,
                    headers.get(KafkaHeaders.RECEIVED_PARTITION) instanceof List<?> p ? p : null,
                    headers.get(KafkaHeaders.OFFSET) instanceof List<?> o ? o : null,
                    message.getPayload().size());
        }

        BatchListenerFailedException failedAt(int index, RuntimeException cause, String source) {
            var text = "A retryable failure processing a batch from " + source
                    + "; redelivering from the failed record";
            if (topics != null && partitions != null && offsets != null
                    && topics.size() == size && partitions.size() == size && offsets.size() == size
                    && index < size
                    && partitions.get(index) instanceof Number partition
                    && offsets.get(index) instanceof Number offset) {
                return new BatchListenerFailedException(text, cause, new ConsumerRecord<>(
                        String.valueOf(topics.get(index)), partition.intValue(), offset.longValue(), null, null));
            }
            return new BatchListenerFailedException(text, cause, index);
        }
    }

}
