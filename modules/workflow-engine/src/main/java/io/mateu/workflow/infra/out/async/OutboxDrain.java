package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntity;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntityRepository;
import io.mateu.workflow.infra.out.persistence.OutboxMessageStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;

/**
 * Drains a bounded batch of pending outbox messages, claiming them with row locks so any number
 * of pods can relay at once without electing a leader between them.
 *
 * <p>Each pass is one transaction: claim ids (skipping what other pods hold), deliver, mark the
 * batch Sent, commit. Delivery happens <b>before</b> the rows are marked, and a crash anywhere in
 * between rolls the transaction back and releases the locks, so another pod redelivers — the
 * at-least-once contract the relay always had, unchanged. Marking first would lose messages.
 *
 * <p>That ordering is necessary and was not sufficient. It only means anything if a delivery that
 * fails <em>says so</em>, and for a long time it could not: the send was asynchronous and its
 * return value discarded, so a broker that was down still produced a row marked Sent. During a
 * ninety-second broker outage, 71 of 642 912 messages were lost that way. The publisher now
 * throws when the broker refuses, which lands in the catch below and leaves the row Pending —
 * and the applications configure the producer synchronously so that refusal is knowable at all.
 *
 * <p>The batch is bounded for a reason beyond memory: the claim holds row locks for as long as
 * the transaction runs, so the batch size is really a bound on how long other pods can be kept
 * from those rows.
 *
 * <h2>The ack barrier</h2>
 *
 * <p>Synchronous sends are what make a refusal knowable, and doing them one after another is what
 * made the batch cost messages × round trip on a single relay thread. Those are separable. The
 * batch is split by partition key: a key's messages go in order, different keys go at once, and
 * the pass returns only when all of them have finished. Nothing about the delivery contract moves
 * — each send still blocks, still throws when refused, still leaves its own row Pending — but the
 * acks are awaited together, so the batch costs the slowest group rather than the sum, and the
 * producer can batch concurrent records into far fewer broker requests.
 *
 * <p>Splitting by key is what makes it safe rather than merely fast: two events of one process are
 * never in flight together, which is the ordering that keying events by process exists to give.
 * Across pods it is {@link OutboxKeyOrder}'s: a claimed row that would overtake an older pending
 * row of its process held by another pod (or left Pending by a failed send) is not sent this pass.
 * Concurrency is 1 by default, so this is opt-in until a cluster measurement says otherwise.
 */
@Component
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa")
@RequiredArgsConstructor
@Slf4j
public class OutboxDrain {

    final OutboxMessageEntityRepository outboxMessageEntityRepository;

    final io.mateu.workflow.application.out.WorkflowTracing workflowTracing;
    final io.mateu.workflow.application.out.WorkflowMetrics workflowMetrics;
    final JdbcTemplate jdbcTemplate;
    final DbLockDialect dbLockDialect;
    final TransactionTemplate transactionTemplate;

    /**
     * How many partition keys of a batch may be in flight at once.
     *
     * <p>Defaults to 1, which is the behaviour that shipped before the barrier existed. Raising it
     * is a throughput decision to take against a measurement — {@code
     * eventconductor.outbox.batch.deliver} against {@code eventconductor.outbox.relay.draining} —
     * and not a guess; the distributed suite is what says it is safe on a real broker.
     *
     * <p>It is bounded by design. The claim holds row locks for as long as the transaction runs,
     * and the transaction now runs until the slowest group's acks arrive, so this trades relay
     * latency against how long other pods are kept from those rows.
     */
    @org.springframework.beans.factory.annotation.Value("${workflow.outbox.relay-concurrency:0}")
    int relayConcurrency;

    /** Opens the sealed variables of a row (sealed by the repository that wrote it) before it is delivered. */
    io.mateu.workflow.application.services.VariableProtection protection =
            io.mateu.workflow.application.services.VariableProtection.none();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void protection(io.mateu.workflow.application.services.VariableProtection protection) {
        this.protection = protection;
    }

    private volatile ExecutorService sendPool;

    /**
     * Virtual threads, and the reason is the whole point of this.
     *
     * <p>A send blocks on its ack. On platform threads that made concurrency expensive, so the pool
     * was sized to a handful — and the producer therefore never held more than a handful of records
     * at once, which is not enough for it to build a batched request out of. The round trip was
     * amortised across four records rather than across the batch. A thread that is only waiting for
     * a network answer does not need to be a platform thread, so this costs nothing to raise and the
     * producer's own {@code batch.size} / {@code linger.ms} finally have something to work with.
     *
     * <p>Nothing about the guarantees moves. Sends stay synchronous, so a refusal is still knowable
     * message by message; within a partition key they stay strictly sequential, so per-process
     * ordering remains true by construction rather than becoming a property of
     * {@code max.in.flight.requests.per.connection}. What changes is how many <em>keys</em> are in
     * flight at once, which is the axis that fills a broker request.
     *
     * <p>{@code relay-concurrency <= 0} is one thread per key in the batch. It is bounded anyway, by
     * the batch: {@code batchSize} keys is the most this can ever start.
     */
    @jakarta.annotation.PostConstruct
    void startSendPool() {
        if (relayConcurrency == 1) {
            // Inline on the calling thread — the behaviour that shipped before any of this existed,
            // down to the code path, and still what a single-key batch gets.
            return;
        }
        // Named, because an unnamed virtual thread is a thread dump you cannot read: a stuck relay
        // looks like a hundred anonymous parked threads unless they say what they are.
        sendPool = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("outbox-send-", 0).factory());
        sendLimit = relayConcurrency > 1 ? new Semaphore(relayConcurrency) : null;
    }

    /**
     * How many sends may be in flight, when the operator wants a ceiling. Null is no ceiling beyond
     * the batch itself — see {@code startSendPool} on why that is affordable now, and
     * {@code deliverGroups} on what it costs the transaction.
     */
    private volatile Semaphore sendLimit;

    @jakarta.annotation.PreDestroy
    void stopSendPool() {
        if (sendPool != null) {
            sendPool.shutdownNow();
        }
    }

    /**
     * What one pass achieved. {@code claimed} says whether there may be more waiting — a full
     * batch means keep going — and {@code settled} says whether the pass actually moved anything
     * out of Pending. A caller that loops on a full batch must stop when nothing settles, or a
     * batch of permanently failing messages becomes a hot loop.
     */
    public record Result(int claimed, int settled, int heldBack) {

        public Result(int claimed, int settled) {
            this(claimed, settled, 0);
        }

        /**
         * Claimed rows that had a chance to settle — what a stall is measured against. A pass whose
         * every row waited behind another pod's older event of the same process has not failed at
         * anything and must not be paced like a broker that refuses.
         */
        public int attempted() {
            return claimed - heldBack;
        }
    }

    /**
     * Claims and delivers up to {@code batchSize} messages. A message whose payload cannot be
     * deserialized can never succeed, so it is parked as Error rather than retried forever; one
     * whose delivery throws is left Pending for the next pass.
     */
    public Result drain(int batchSize, Consumer<DomainEvent> deliver) {
        var result = transactionTemplate.execute(status -> {
            var messages = claim(batchSize);
            if (messages.isEmpty()) {
                return new Result(0, 0);
            }
            var claimed = messages.size();
            var heldBack = holdBackWhatWouldOvertake(messages);
            var poisoned = new ArrayList<OutboxMessageEntity>();
            var byKey = groupByPartitionKey(messages, poisoned);

            // Wall-clock across the whole batch, which is what the relay thread actually pays.
            // Before the batch ran concurrently this was also the sum of the acks; now the two
            // differ, and the gap between them is the win.
            var startedAt = System.nanoTime();
            var sent = deliverGroups(byKey, deliver);
            workflowMetrics.outboxBatchDelivered(messages.size(), Duration.ofNanos(System.nanoTime() - startedAt));

            sent.forEach(message -> message.setStatus(OutboxMessageStatus.Sent.name()));
            poisoned.forEach(message -> message.setStatus(OutboxMessageStatus.Error.name()));
            if (!sent.isEmpty() || !poisoned.isEmpty()) {
                var touched = new ArrayList<>(sent);
                touched.addAll(poisoned);
                outboxMessageEntityRepository.saveAll(touched);
            }
            return new Result(claimed, sent.size() + poisoned.size(), heldBack);
        });
        return result == null ? new Result(0, 0) : result;
    }

    /**
     * Loads the claimed batch and splits it into one ordered list per partition key.
     *
     * <p><b>The sort is a fix, not a formality.</b> {@code claimPendingOutboxSql} orders by
     * timestamp, but {@code findAllById} makes no such promise — it is an {@code id in (...)} whose
     * result order is whatever the database returns. So a batch holding two events of the same
     * process could already be published in the wrong order, which is precisely the guarantee that
     * keying events by process was introduced to provide. Restoring the order here is what makes
     * sending the groups concurrently safe rather than merely faster.
     *
     * <p>An event with no partition key belongs to no process and is ordered against nothing, so it
     * gets a group of its own — that is not a loophole, it is the same statement the unkeyed send
     * already makes to Kafka.
     */
    private Map<String, List<Delivery>> groupByPartitionKey(
            List<OutboxMessageEntity> messages, List<OutboxMessageEntity> poisoned) {
        var claimedAt = LocalDateTime.now();
        messages.sort(Comparator
                .comparing(OutboxMessageEntity::getTimestamp, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(OutboxMessageEntity::getId, Comparator.nullsFirst(Comparator.naturalOrder())));

        var byKey = new LinkedHashMap<String, List<Delivery>>();
        for (var message : messages) {
            if (message.getTimestamp() != null) {
                workflowMetrics.outboxMessageRelayed(Duration.between(message.getTimestamp(), claimedAt));
            }
            final DomainEvent payload;
            try {
                payload = (DomainEvent) pojoFromJson(protection.openPayload(message.getPayload()),
                        OutboxMessages.messageClass(message.getMessageType()));
            } catch (Exception e) {
                log.error("Outbox message {} cannot be deserialized, marking as Error",
                        message.getId(), e);
                poisoned.add(message);
                continue;
            }
            var key = payload.partitionKey();
            var groupKey = (key == null || key.isBlank()) ? " unkeyed:" + message.getId() : key;
            byKey.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(new Delivery(message, payload));
        }
        return byKey;
    }

    /**
     * Sends every group, each group in order, groups against each other in parallel — and returns
     * only once all of them have finished. That join is the ack barrier: sends stay synchronous, so
     * a refusal is still knowable message by message, but the batch's acks are awaited together
     * instead of one after another. A batch of a hundred used to cost a hundred round trips in
     * series; it now costs as many as the longest single group, and the producer gets to batch the
     * concurrent records into far fewer broker requests.
     *
     * <p>Concurrency of one — the default — sends inline on the calling thread and is exactly the
     * behaviour that shipped before this existed, down to the code path.
     */
    private List<OutboxMessageEntity> deliverGroups(
            Map<String, List<Delivery>> byKey, Consumer<DomainEvent> deliver) {
        if (byKey.isEmpty()) {
            return List.of();
        }
        var pool = sendPool;
        if (pool == null || byKey.size() == 1) {
            var sent = new ArrayList<OutboxMessageEntity>();
            byKey.values().forEach(group -> sent.addAll(deliverInOrder(group, deliver)));
            return sent;
        }
        var limit = sendLimit;
        var futures = byKey.values().stream()
                .map(group -> CompletableFuture.supplyAsync(() -> {
                    // The ceiling, when one is configured. Acquired inside the task rather than
                    // before submitting, so the wait is a parked virtual thread and not the relay
                    // thread queueing them one at a time.
                    if (limit == null) {
                        return deliverInOrder(group, deliver);
                    }
                    limit.acquireUninterruptibly();
                    try {
                        return deliverInOrder(group, deliver);
                    } finally {
                        limit.release();
                    }
                }, pool))
                .toList();
        var sent = new ArrayList<OutboxMessageEntity>();
        for (var future : futures) {
            try {
                sent.addAll(future.join());
            } catch (Exception e) {
                // deliverInOrder catches per message, so reaching here means the task itself died.
                // Its rows stay Pending, which is the same answer a refused send gets.
                log.error("An outbox send group failed outright, its messages stay Pending", e);
            }
        }
        return sent;
    }

    /**
     * One partition key's messages, oldest first, stopping at the first failure.
     *
     * <p>Stopping matters. The messages left behind stay Pending and are redelivered next pass, so
     * carrying on past a failure would put a later event on the topic ahead of an earlier one that
     * is about to be retried — a reordering the engine would have no way to detect, manufactured by
     * the very code meant to preserve order. A poisoned message is different and is already gone
     * from this list: it can never be delivered, so blocking its process forever would be the worse
     * of the two evils.
     */
    private List<OutboxMessageEntity> deliverInOrder(List<Delivery> group, Consumer<DomainEvent> deliver) {
        var sent = new ArrayList<OutboxMessageEntity>();
        for (var delivery : group) {
            var message = delivery.message();
            try {
                log.debug("Relaying outbox message {}", message.getId());
                // Delivered as a continuation of the trace that produced the event, not of the
                // relay pass that happens to be draining it. Without this the send belongs to
                // no trace at all and the consumer on the other side starts a fresh one, so a
                // process reads as a series of unrelated traces rather than one.
                workflowTracing.continuing(message.traceContext(), "outbox relay", java.util.Map.of(),
                        () -> deliver.accept(delivery.payload()));
                sent.add(message);
            } catch (Exception e) {
                // Left Pending: the next pass picks it up again — and so are the rest of this key's
                // messages, deliberately.
                log.error("Failed to relay outbox message {}, will retry next cycle "
                        + "(holding back {} later message(s) of the same process to keep their order)",
                        message.getId(), group.size() - sent.size() - 1, e);
                break;
            }
        }
        return sent;
    }

    private record Delivery(OutboxMessageEntity message, DomainEvent payload) {}

    private List<OutboxMessageEntity> claim(int batchSize) {
        return load(jdbcTemplate.query(
                dbLockDialect.claimPendingOutboxSql(),
                ps -> ps.setInt(1, batchSize),
                (rs, rowNum) -> rs.getString(1)));
    }

    /**
     * Removes from {@code messages} the rows that would overtake an older pending row of their key
     * this pass does not hold, and returns how many. They stay Pending — locked until this
     * transaction ends, so no other pod sends them meanwhile either — and go in a later pass. See
     * {@link OutboxKeyOrder} for why this is a read after the claim rather than part of it.
     *
     * <p>The keys are the rows' {@code partition_key} column, the one the read filters on. A batch
     * whose rows carry no key at all — or none that repeats a key with anything older — costs the
     * read and nothing else.
     */
    private int holdBackWhatWouldOvertake(List<OutboxMessageEntity> messages) {
        var claimed = new ArrayList<OutboxKeyOrder.Row>(messages.size());
        var keys = new java.util.LinkedHashSet<String>();
        LocalDateTime newest = null;
        for (var message : messages) {
            claimed.add(new OutboxKeyOrder.Row(message.getId(), message.getPartitionKey(), message.getTimestamp()));
            if (message.getPartitionKey() != null) {
                keys.add(message.getPartitionKey());
                if (message.getTimestamp() != null && (newest == null || message.getTimestamp().isAfter(newest))) {
                    newest = message.getTimestamp();
                }
            }
        }
        if (keys.isEmpty()) {
            return 0;
        }
        var bound = java.sql.Timestamp.valueOf(newest == null ? LocalDateTime.now() : newest);
        var keyList = new ArrayList<>(keys);
        var pending = new ArrayList<OutboxKeyOrder.Row>();
        for (int from = 0; from < keyList.size(); from += OutboxKeyOrder.MAX_KEYS_PER_STATEMENT) {
            var chunk = keyList.subList(from, Math.min(keyList.size(), from + OutboxKeyOrder.MAX_KEYS_PER_STATEMENT));
            pending.addAll(jdbcTemplate.query(
                    OutboxKeyOrder.pendingOfKeysSql(chunk.size()),
                    ps -> {
                        for (int i = 0; i < chunk.size(); i++) {
                            ps.setString(i + 1, chunk.get(i));
                        }
                        ps.setTimestamp(chunk.size() + 1, bound);
                    },
                    (rs, rowNum) -> {
                        var timestamp = rs.getTimestamp(3);
                        return new OutboxKeyOrder.Row(rs.getString(1), rs.getString(2),
                                timestamp == null ? null : timestamp.toLocalDateTime());
                    }));
        }
        var sendable = OutboxKeyOrder.sendable(claimed, pending);
        var before = messages.size();
        messages.removeIf(message -> !sendable.contains(message.getId()));
        var heldBack = before - messages.size();
        if (heldBack > 0) {
            log.debug("Holding back {} claimed outbox message(s) behind an older pending event of the "
                    + "same process held elsewhere", heldBack);
        }
        return heldBack;
    }



    private List<OutboxMessageEntity> load(List<String> ids) {
        var messages = new ArrayList<OutboxMessageEntity>();
        if (!ids.isEmpty()) {
            outboxMessageEntityRepository.findAllById(ids).forEach(messages::add);
        }
        return messages;
    }
}
