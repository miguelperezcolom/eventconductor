package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The relay loop against a broker that will not take anything.
 *
 * <p>{@link RelayPaceTest} pins the arithmetic; this pins that the loop actually uses it, which is
 * where the bug was. The inner loop already stopped early on a batch that settled nothing — what
 * kept the relay hot was the OUTER one: it waits on {@link OutboxSignal}, every commit raises that
 * signal, and a commit is not news about the broker. So the assertion that matters here is a
 * negative one, {@code verify(signal, never()).awaitWork(...)}: a relay that is stalling must not
 * be listening to writes, because writes are exactly what it is drowning in.
 */
class OutboxRelayBackoffTest {

    private final OutboxDrain drain = mock(OutboxDrain.class);
    private final OutboxSignal signal = mock(OutboxSignal.class);
    private final WorkflowMetrics metrics = mock(WorkflowMetrics.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final DbLockDialect dbLockDialect = mock(DbLockDialect.class);

    private OutboxRelay relay;

    @AfterEach
    void stopTheRelay() {
        if (relay != null) {
            relay.stop();
        }
    }

    /** Wires a relay whose every pass returns what {@code pass} says, backing off in milliseconds. */
    @SuppressWarnings("unchecked")
    private OutboxRelay relayWhoseEveryPassIs(Supplier<OutboxDrain.Result> pass) throws java.sql.SQLException {
        when(dbLockDialect.tryRelayGate(any())).thenReturn(true);
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation ->
                ((ConnectionCallback<Object>) invocation.getArgument(0)).doInConnection(mock(Connection.class)));
        when(drain.drain(anyInt(), any())).thenAnswer(invocation -> pass.get());

        var relay = new OutboxRelay(drain, signal, metrics, mock(StreamBridge.class),
                jdbcTemplate, dbLockDialect);
        relay.pollIntervalMs = 50;
        relay.batchSize = 500;
        // Milliseconds rather than the shipped hundreds, so the test measures the shape and not
        // the clock. No jitter, for the same reason.
        relay.relayBackoffBaseMs = 1;
        relay.relayBackoffMaxMs = 4;
        relay.relayBackoffJitter = 0.0;
        return relay;
    }

    @Test
    @DisplayName("a refusing broker paces the relay instead of waking it on every write")
    void backsOffInsteadOfSpinningOnTheSignal() throws Exception {
        var stalls = new CountDownLatch(5);
        relay = relayWhoseEveryPassIs(() -> new OutboxDrain.Result(500, 0));
        // The metric is the loop's own report that it treated the pass as a stall.
        org.mockito.Mockito.doAnswer(invocation -> {
            stalls.countDown();
            return null;
        }).when(metrics).outboxRelayStalled();

        relay.iterate();

        assertThat(stalls.await(5, TimeUnit.SECONDS))
                .as("the relay should have reported five stalled passes")
                .isTrue();
        // The whole point: while stalling it is not listening for writes, so a pod under load
        // cannot drive it back at the broker at the rate it is committing.
        verify(signal, never()).awaitWork(anyLong());
    }

    @Test
    @DisplayName("an empty outbox keeps waiting on the signal, and never backs off")
    void anIdleRelayIsNotAStalledOne() throws Exception {
        var waits = new CountDownLatch(5);
        relay = relayWhoseEveryPassIs(() -> new OutboxDrain.Result(0, 0));
        when(signal.awaitWork(anyLong())).thenAnswer(invocation -> {
            waits.countDown();
            return false;
        });

        relay.iterate();

        assertThat(waits.await(5, TimeUnit.SECONDS))
                .as("an idle relay should keep waiting on the signal")
                .isTrue();
        // Backing off here would mean an idle engine pacing itself down to the cap and then taking
        // seconds to relay the next message anyone wrote.
        verify(metrics, never()).outboxRelayStalled();
    }

    @Test
    @DisplayName("the relay comes back to the signal as soon as a pass settles something")
    void recoveryReturnsToTheSignal() throws Exception {
        var passes = new AtomicInteger();
        var waits = new CountDownLatch(1);
        // Three passes with the broker down, then it comes back.
        relay = relayWhoseEveryPassIs(() -> passes.getAndIncrement() < 3
                ? new OutboxDrain.Result(500, 0)
                : new OutboxDrain.Result(10, 10));
        when(signal.awaitWork(anyLong())).thenAnswer(invocation -> {
            waits.countDown();
            return false;
        });

        relay.iterate();

        assertThat(waits.await(5, TimeUnit.SECONDS))
                .as("once a pass settles something the relay should wait on the signal again")
                .isTrue();
    }

    /**
     * The chaos tests freeze every relay at once by taking the gate exclusively. A relay that was
     * not allowed to run has not failed at anything, so pacing it down would both misreport an
     * outage and change what those tests are timing.
     */
    @Test
    @DisplayName("a relay that could not take the gate has not stalled")
    void notBeingAllowedToRunIsNotAStall() throws Exception {
        var waits = new CountDownLatch(3);
        relay = relayWhoseEveryPassIs(() -> new OutboxDrain.Result(500, 0));
        when(dbLockDialect.tryRelayGate(any())).thenReturn(false);
        when(signal.awaitWork(anyLong())).thenAnswer(invocation -> {
            waits.countDown();
            return false;
        });

        relay.iterate();

        assertThat(waits.await(5, TimeUnit.SECONDS)).isTrue();
        verify(metrics, never()).outboxRelayStalled();
    }
}
