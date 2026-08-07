package io.mateu.workflow.infra.out.async;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.out.WorkflowTracing;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.domain.ProcessCreated;
import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntity;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntityRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ack barrier: a batch's partition keys are sent concurrently, each key strictly in order.
 *
 * <p>The speed is the easy half and it is not what these tests are for. Sending concurrently is
 * only admissible because two events of one process are never in flight together, and that property
 * has three ways to break — the group order, the failure path, and the pre-existing detail that the
 * claim orders by timestamp while {@code findAllById} does not promise to return anything in
 * particular. Each one below is a way a process could take its transitions backwards, silently,
 * with every existing test still green.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxDrainAckBarrierTest {

    @Mock OutboxMessageEntityRepository repository;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock DbLockDialect dbLockDialect;
    @Mock TransactionTemplate transactionTemplate;

    private OutboxDrain drain;

    @AfterEach
    void stopPool() {
        if (drain != null) {
            drain.stopSendPool();
        }
    }

    private OutboxDrain drainWithConcurrency(int concurrency) {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.getArgument(0, TransactionCallback.class).doInTransaction(null));
        when(dbLockDialect.claimPendingOutboxSql()).thenReturn("select id from outbox ...");
        drain = new OutboxDrain(repository, WorkflowTracing.NOOP, WorkflowMetrics.NOOP, jdbcTemplate,
                dbLockDialect, transactionTemplate);
        drain.relayConcurrency = concurrency;
        drain.startSendPool();
        return drain;
    }

    private void claims(String... ids) {
        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(ids));
    }

    /** Returned in an order the database was never asked to preserve — which is the real contract. */
    private void databaseReturnsShuffled(OutboxMessageEntity... messages) {
        var shuffled = new ArrayList<>(List.of(messages));
        Collections.reverse(shuffled);
        when(repository.findAllById(any())).thenReturn(shuffled);
    }

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 7, 12, 0);

    private static OutboxMessageEntity message(String id, DomainEvent event, int secondsAfterT0) {
        return new OutboxMessageEntity(id, T0.plusSeconds(secondsAfterT0), "Pending",
                event.getClass().getName(), JsonSerializer.toJson(event), null);
    }

    private static ProcessCreated event(String processId) {
        return new ProcessCreated(processId, List.of());
    }

    private static List<String> statusesOfSaved(OutboxMessageEntityRepository repository) {
        var captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(captor.capture());
        var statuses = new ArrayList<String>();
        ((Iterable<OutboxMessageEntity>) captor.getValue()).forEach(m -> statuses.add(m.getStatus()));
        return statuses;
    }

    @Test
    void oneProcessesMessagesGoInTimestampOrderEvenWhenTheDatabaseHandsThemBackShuffled() {
        // The bug this closes predates the barrier: the claim orders by timestamp, findAllById is an
        // "id in (...)" whose result order is the database's business. Two events of one process
        // could already reach the topic backwards.
        claims("m1", "m2", "m3");
        databaseReturnsShuffled(
                message("m1", new Keyed("p-1", 1), 1),
                message("m2", new Keyed("p-1", 2), 2),
                message("m3", new Keyed("p-1", 3), 3));
        var delivered = new ArrayList<Integer>();

        var result = drainWithConcurrency(4).drain(100, e -> delivered.add(((Keyed) e).seq()));

        assertThat(result.settled()).isEqualTo(3);
        assertThat(delivered).containsExactly(1, 2, 3);
        assertThat(statusesOfSaved(repository)).containsExactly("Sent", "Sent", "Sent");
    }

    @Test
    void oneProcessStaysStrictlySerialWhileOtherProcessesRunAlongsideIt() {
        // The property the whole design rests on, and the case that actually exercises the pool: a
        // single-key batch takes an inline shortcut, so only a batch with a second key proves that
        // the first key's messages are still held to one at a time.
        claims("a1", "a2", "a3", "b1");
        databaseReturnsShuffled(
                message("a1", new Keyed("p-1", 1), 1),
                message("a2", new Keyed("p-1", 2), 2),
                message("a3", new Keyed("p-1", 3), 3),
                message("b1", new Keyed("p-2", 1), 4));

        var activePerKey = new java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>();
        var peakForP1 = new AtomicInteger();
        var threads = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        drainWithConcurrency(4).drain(100, e -> {
            var key = ((Keyed) e).key();
            threads.add(Thread.currentThread().getName());
            var active = activePerKey.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            if ("p-1".equals(key)) {
                peakForP1.accumulateAndGet(active, Math::max);
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            activePerKey.get(key).decrementAndGet();
        });

        assertThat(peakForP1).hasValue(1);
        // And it really ran under the pool rather than taking the single-group inline shortcut,
        // or the assertion above would hold for the boring reason. Asserted on the thread the
        // sends happened on: whether two keys overlap in time is the scheduler's business, and an
        // earlier version of this test asked that question and failed on a loaded CI runner while
        // the engine was behaving perfectly.
        assertThat(threads).anyMatch(name -> name.startsWith("outbox-send"));
    }

    @Test
    void differentProcessesDoGoAtOnce() {
        claims("m1", "m2", "m3");
        databaseReturnsShuffled(
                message("m1", event("p-1"), 1),
                message("m2", event("p-2"), 2),
                message("m3", event("p-3"), 3));

        assertThat(sendsThatMetEachOther(drainWithConcurrency(3), 3)).isEqualTo(3);
    }

    @Test
    void eventsBelongingToNoProcessDoNotSerialiseAgainstEachOther() {
        // A null partition key says "any pod may handle this" — a log line, a resource. Ordering
        // them against each other would cost balance for a guarantee nobody asked for.
        claims("m1", "m2");
        databaseReturnsShuffled(
                message("m1", new Unkeyed("a"), 1),
                message("m2", new Unkeyed("b"), 2));

        assertThat(sendsThatMetEachOther(drainWithConcurrency(2), 2)).isEqualTo(2);
    }

    /**
     * Every send announces itself and then waits for the others to arrive.
     *
     * <p>A rendezvous rather than "sleep and see how many overlapped": whether two sends happen to
     * overlap is up to the scheduler, so sampling it is a test that fails on a loaded machine for
     * no reason. Here concurrency is what makes the wait return at all — if the sends were
     * serialized the first one would sit out its timeout with nobody coming, and the count could
     * never reach the expected number. Correct code passes in microseconds.
     */
    private int sendsThatMetEachOther(OutboxDrain drain, int expected) {
        var rendezvous = new java.util.concurrent.CountDownLatch(expected);
        var met = new AtomicInteger();
        drain.drain(100, e -> {
            rendezvous.countDown();
            try {
                if (rendezvous.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    met.incrementAndGet();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        return met.get();
    }

    @Test
    void aFailedSendHoldsBackTheLaterMessagesOfItsOwnProcess() {
        // Carrying on past the failure would publish m3 while m2 waits Pending for the next pass —
        // a reordering manufactured by the code that exists to prevent one.
        claims("m1", "m2", "m3");
        databaseReturnsShuffled(
                message("m1", new Keyed("p-1", 1), 1),
                message("m2", new Keyed("p-1", 2), 2),
                message("m3", new Keyed("p-1", 3), 3));
        var delivered = new ArrayList<Integer>();

        var result = drainWithConcurrency(3).drain(100, e -> {
            delivered.add(((Keyed) e).seq());
            if (delivered.size() == 2) {
                throw new IllegalStateException("the broker refused this one");
            }
        });

        // Two attempted, one accepted, and the third never left: it must not overtake the second.
        assertThat(delivered).containsExactly(1, 2);
        assertThat(result.claimed()).isEqualTo(3);
        assertThat(result.settled()).isEqualTo(1);
        assertThat(statusesOfSaved(repository)).containsExactly("Sent");
    }

    @Test
    void aProcessThatFailsDoesNotHoldBackAnyOther() {
        claims("m1", "m2");
        databaseReturnsShuffled(
                message("m1", event("doomed"), 1),
                message("m2", event("healthy"), 2));
        var delivered = Collections.synchronizedList(new ArrayList<String>());

        var result = drainWithConcurrency(2).drain(100, e -> {
            var processId = ((ProcessCreated) e).processId();
            if ("doomed".equals(processId)) {
                throw new IllegalStateException("the broker refused this one");
            }
            delivered.add(processId);
        });

        assertThat(delivered).containsExactly("healthy");
        assertThat(result.settled()).isEqualTo(1);
    }

    @Test
    void aPoisonedMessageIsParkedWithoutBlockingItsProcessForever() {
        // The one case where skipping ahead is right: a message that can never be deserialized can
        // never be delivered, so holding its process for it would stop the process permanently.
        claims("bad", "good");
        databaseReturnsShuffled(
                new OutboxMessageEntity("bad", T0.plusSeconds(1), "Pending", "java.lang.Runtime", "{}", null),
                message("good", event("p-1"), 2));
        var delivered = new ArrayList<String>();

        var result = drainWithConcurrency(2).drain(100, e -> delivered.add(((ProcessCreated) e).processId()));

        assertThat(delivered).containsExactly("p-1");
        assertThat(result.settled()).isEqualTo(2);
        assertThat(statusesOfSaved(repository)).containsExactlyInAnyOrder("Sent", "Error");
    }

    @Test
    void concurrencyOfOneRunsInlineOnTheCallingThread() {
        // The default, and it has to remain the code path that shipped: no pool, no hand-off.
        claims("m1", "m2");
        databaseReturnsShuffled(
                message("m1", event("p-1"), 1),
                message("m2", event("p-2"), 2));
        var threads = new ArrayList<String>();

        var result = drainWithConcurrency(1).drain(100, e -> threads.add(Thread.currentThread().getName()));

        assertThat(result.settled()).isEqualTo(2);
        assertThat(threads).containsOnly(Thread.currentThread().getName());
    }

    /** An event that belongs to no process: {@link DomainEvent#partitionKey()} defaults to null. */
    public record Unkeyed(String note) implements DomainEvent {}

    /**
     * Carries its own sequence number, so a test can assert the order events reached the topic in
     * rather than merely how many did. {@code ProcessCreated} cannot: every event of one process
     * looks identical, which is exactly how a reordering would hide.
     */
    public record Keyed(String key, int seq) implements DomainEvent {
        @Override
        public String partitionKey() {
            return key;
        }
    }
}
