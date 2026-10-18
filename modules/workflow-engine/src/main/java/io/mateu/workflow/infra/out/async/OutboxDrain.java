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
 * Across pods that order is the claim's job: only the oldest pending row of a key can be claimed,
 * so a process's events are never split between relays (see {@code claim}).
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
    public record Result(int claimed, int settled) {}

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
            return new Result(messages.size(), sent.size() + poisoned.size());
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
                payload = (DomainEvent) pojoFromJson(message.getPayload(),
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

    /**
     * Claims up to {@code batchSize} rows in two steps, both inside the pass's transaction.
     *
     * <p><b>Heads first.</b> {@code claimPendingOutboxSql} only matches a row that is the oldest
     * pending one of its partition key, and skips what other pods hold. That is what keeps a
     * process's events in order <em>across</em> pods: while pod A holds (or keeps failing to send)
     * event 1 of a process, event 2 is not a head, so pod B cannot claim it and publish it first —
     * whether A's lock is what B ran into, A's batch limit cut between the two, or A's send failed
     * and left event 1 Pending. Plain {@code SKIP LOCKED} let B take event 2 in all three cases; the
     * ordering this class keeps inside one batch only ever held inside one pod.
     *
     * <p><b>Then the rest of those runs.</b> Heads alone would relay one event per process per pass,
     * and the relay only loops on a full batch, so a process's next event would wait for the next
     * poll. Its successors cannot be claimed by anyone else while its head is Pending, so this pass
     * takes them too, oldest first, up to what is left of the batch.
     *
     * <p>Keys come from the rows' {@code partition_key} column, which is what the claim's predicate
     * reads; a row without one has no successors to fetch.
     */
    private List<OutboxMessageEntity> claim(int batchSize) {
        var heads = jdbcTemplate.query(
                dbLockDialect.claimPendingOutboxSql(),
                ps -> ps.setInt(1, batchSize),
                (rs, rowNum) -> rs.getString(1));
        var messages = load(heads);
        var claimed = new java.util.HashSet<>(heads);
        var keys = new ArrayList<>(messages.stream()
                .map(OutboxMessageEntity::getPartitionKey)
                .filter(key -> key != null)
                .distinct()
                .toList());
        // Chunked so the IN list stays far below any database's limit on it (Oracle's is 1000)
        // whatever the batch size. Each key is in one chunk, so each still gets a prefix of its run.
        for (int from = 0; from < keys.size() && messages.size() < batchSize; from += MAX_KEYS_PER_CLAIM) {
            var chunk = keys.subList(from, Math.min(keys.size(), from + MAX_KEYS_PER_CLAIM));
            var remaining = batchSize - messages.size();
            // The runs come back with their heads, which this pass holds already: ask for that many
            // more, drop them, and keep the oldest of the rest. Each key's share stays a prefix.
            var limit = remaining + chunk.size();
            var run = jdbcTemplate.query(
                    dbLockDialect.claimFollowingOutboxSql(chunk.size()),
                    ps -> {
                        for (int i = 0; i < chunk.size(); i++) {
                            ps.setString(i + 1, chunk.get(i));
                        }
                        ps.setInt(chunk.size() + 1, limit);
                    },
                    (rs, rowNum) -> rs.getString(1));
            var followers = run.stream().filter(claimed::add).limit(remaining).toList();
            messages.addAll(load(followers));
        }
        return messages;
    }

    static final int MAX_KEYS_PER_CLAIM = 500;

    private List<OutboxMessageEntity> load(List<String> ids) {
        var messages = new ArrayList<OutboxMessageEntity>();
        if (!ids.isEmpty()) {
            outboxMessageEntityRepository.findAllById(ids).forEach(messages::add);
        }
        return messages;
    }
}
