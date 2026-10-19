package io.mateu.workflow.infra.in.async;

import io.mateu.workflow.application.out.DeadLetterPublisher;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventUseCase;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.kafka.listener.BatchListenerFailedException;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The contract of the batch consumer, and above all its one hard invariant: <em>a process's events
 * are never processed concurrently</em>. Parallelism, when enabled, is only ever across distinct
 * processes — which is safe because the events of one process share a Kafka partition, so they reach
 * one consumer thread in order and land in one group here. These tests pin that: order within a
 * process survives parallelism, different processes genuinely overlap, and the retryable-vs-park
 * error handling is unchanged whether groups run serially or concurrently.
 */
class OrchestratorKafkaConsumerConfigTest {

    private final ProcessUpstreamEventUseCase upstream = mock(ProcessUpstreamEventUseCase.class);
    private final DeadLetterPublisher deadLetters = mock(DeadLetterPublisher.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private OrchestratorKafkaConsumerConfig config;

    private OrchestratorKafkaConsumerConfig config(int parallelism) {
        // The transaction template just runs the work, as a committed transaction would.
        doAnswer(invocation -> {
            invocation.getArgument(0, Consumer.class).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        config = new OrchestratorKafkaConsumerConfig(
                mock(ProcessDomainEventUseCase.class), upstream, transactionTemplate, deadLetters);
        config.processParallelism = parallelism;
        config.startProcessExecutor();
        return config;
    }

    @AfterEach
    void tearDown() {
        if (config != null) {
            config.stopProcessExecutor();
        }
    }

    private static DomainEvent event(String processKey, String id) {
        var event = mock(DomainEvent.class);
        // Deep stub not needed; only these two are read.
        org.mockito.Mockito.when(event.partitionKey()).thenReturn(processKey);
        org.mockito.Mockito.when(event.toString()).thenReturn(id);
        return event;
    }

    @Test
    void aProcessEventsStayInOrderWhenGroupsRunInParallel() {
        var seenByProcess = new ConcurrentHashMap<String, List<String>>();
        doAnswer(invocation -> {
            var event = invocation.getArgument(0, ProcessUpstreamEventCommand.class).event();
            seenByProcess.computeIfAbsent(event.partitionKey(), k -> new CopyOnWriteArrayList<>())
                    .add(event.toString());
            return null;
        }).when(upstream).handle(any());

        // Three processes, events interleaved in the batch as they would arrive across partitions.
        var batch = List.of(
                event("A", "a0"), event("B", "b0"), event("A", "a1"),
                event("C", "c0"), event("B", "b1"), event("A", "a2"));

        config(4).consumeUpstream().accept(org.springframework.messaging.support.MessageBuilder.withPayload(batch).build());

        // The invariant: within each process, the engine saw the events in arrival order.
        assertThat(seenByProcess.get("A")).containsExactly("a0", "a1", "a2");
        assertThat(seenByProcess.get("B")).containsExactly("b0", "b1");
        assertThat(seenByProcess.get("C")).containsExactly("c0");
    }

    @Test
    void distinctProcessesReallyRunConcurrently() {
        // A's handler cannot finish until B's has run: with real parallelism B runs on another thread
        // and releases it; if the groups ran serially this would block until the test times out.
        var bHasRun = new CountDownLatch(1);
        var aProceeded = new CountDownLatch(1);
        doAnswer(invocation -> {
            var key = invocation.getArgument(0, ProcessUpstreamEventCommand.class).event().partitionKey();
            if ("A".equals(key)) {
                if (bHasRun.await(5, TimeUnit.SECONDS)) {
                    aProceeded.countDown();
                }
            } else if ("B".equals(key)) {
                bHasRun.countDown();
            }
            return null;
        }).when(upstream).handle(any());

        config(2).consumeUpstream().accept(org.springframework.messaging.support.MessageBuilder.withPayload(List.of(event("A", "a0"), event("B", "b0"))).build());

        assertThat(aProceeded.getCount()).as("A only proceeds once B has run on another thread").isZero();
    }

    @Test
    void aRetryableFailureNamesTheRecordToRedeliverFrom() {
        doAnswer(invocation -> {
            var key = invocation.getArgument(0, ProcessUpstreamEventCommand.class).event().partitionKey();
            if ("A".equals(key)) {
                throw new RecoverableDataAccessException("db blipped");
            }
            return null;
        }).when(upstream).handle(any());

        assertThatThrownBy(() -> config(4).consumeUpstream().accept(org.springframework.messaging.support.MessageBuilder.withPayload(List.of(event("A", "a0"), event("B", "b0"))).build()))
                .isInstanceOf(BatchListenerFailedException.class)
                .hasCauseInstanceOf(RecoverableDataAccessException.class)
                .satisfies(e -> assertThat(((BatchListenerFailedException) e).getIndex()).isZero());
        // A retryable failure never parks: the error handler redelivers and idempotent handlers redo the rest.
        verify(deadLetters, never()).park(any(), any(), any());
    }

    @Test
    void aNonRetryableFailureIsParkedAndTheOtherProcessesStillCommit() {
        var seen = new CopyOnWriteArrayList<String>();
        doAnswer(invocation -> {
            var event = invocation.getArgument(0, ProcessUpstreamEventCommand.class).event();
            if ("A".equals(event.partitionKey())) {
                throw new IllegalStateException("this event is defective forever");
            }
            seen.add(event.toString());
            return null;
        }).when(upstream).handle(any());

        var poison = event("A", "a0");
        assertThatCode(() -> config(4).consumeUpstream().accept(org.springframework.messaging.support.MessageBuilder.withPayload(List.of(poison, event("B", "b0"))).build()))
                .doesNotThrowAnyException();

        verify(deadLetters).park(eq(poison), any(IllegalStateException.class), eq("upstream"));
        assertThat(seen).containsExactly("b0");
    }

    @Test
    void withoutParallelismTheGroupsStillRunAndKeepOrder() {
        var seen = new CopyOnWriteArrayList<String>();
        doAnswer(invocation -> {
            seen.add(invocation.getArgument(0, ProcessUpstreamEventCommand.class).event().toString());
            return null;
        }).when(upstream).handle(any());

        config(1).consumeUpstream().accept(org.springframework.messaging.support.MessageBuilder.withPayload(List.of(event("A", "a0"), event("A", "a1"), event("B", "b0"))).build());

        verify(upstream, times(3)).handle(any());
        assertThat(seen).containsExactly("a0", "a1", "b0");
    }

    /** A batch as the binder delivers it: payloads plus the per-record position header lists. */
    private static org.springframework.messaging.Message<List<DomainEvent>> batch(List<DomainEvent> events, long firstOffset) {
        var topics = new java.util.ArrayList<String>();
        var partitions = new java.util.ArrayList<Integer>();
        var offsets = new java.util.ArrayList<Long>();
        for (int i = 0; i < events.size(); i++) {
            topics.add("upstream");
            partitions.add(3);
            offsets.add(firstOffset + i);
        }
        return org.springframework.messaging.support.MessageBuilder.withPayload(events)
                .setHeader(org.springframework.kafka.support.KafkaHeaders.RECEIVED_TOPIC, topics)
                .setHeader(org.springframework.kafka.support.KafkaHeaders.RECEIVED_PARTITION, partitions)
                .setHeader(org.springframework.kafka.support.KafkaHeaders.OFFSET, offsets)
                .build();
    }

    @Test
    void serially_aConflictStopsTheBatchAndNamesTheFirstRecordOfTheFailedProcess() {
        var seen = new CopyOnWriteArrayList<String>();
        doAnswer(invocation -> {
            var event = invocation.getArgument(0, ProcessUpstreamEventCommand.class).event();
            seen.add(event.toString());
            if ("B".equals(event.partitionKey())) {
                throw new org.springframework.orm.ObjectOptimisticLockingFailureException("process", "B");
            }
            return null;
        }).when(upstream).handle(any());

        // Groups in order of first appearance: A (0, 2), B (1), C (3). B fails at offset 101.
        var events = List.of(event("A", "a0"), event("B", "b0"), event("A", "a1"), event("C", "c0"));
        assertThatThrownBy(() -> config(1).consumeUpstream().accept(batch(events, 100)))
                .isInstanceOfSatisfying(BatchListenerFailedException.class, e -> {
                    assertThat(e.getRecord()).isNotNull();
                    assertThat(e.getRecord().topic()).isEqualTo("upstream");
                    assertThat(e.getRecord().partition()).isEqualTo(3);
                    assertThat(e.getRecord().offset()).isEqualTo(101);
                });
        // C never ran: it is after the failed record and will be redelivered with it.
        assertThat(seen).containsExactly("a0", "a1", "b0");
        verify(deadLetters, never()).park(any(), any(), any());
    }

    @Test
    void inParallel_everyGroupRunsAndTheEarliestFailedGroupIsNamed() {
        var seen = new CopyOnWriteArrayList<String>();
        doAnswer(invocation -> {
            var event = invocation.getArgument(0, ProcessUpstreamEventCommand.class).event();
            seen.add(event.toString());
            if ("B".equals(event.partitionKey()) || "D".equals(event.partitionKey())) {
                throw new org.springframework.dao.CannotAcquireLockException("deadlock detected");
            }
            return null;
        }).when(upstream).handle(any());

        var events = List.of(event("A", "a0"), event("D", "d0"), event("C", "c0"), event("B", "b0"));
        assertThatThrownBy(() -> config(4).consumeUpstream().accept(batch(events, 7)))
                .isInstanceOfSatisfying(BatchListenerFailedException.class,
                        e -> assertThat(e.getRecord().offset()).as("D, at index 1, is the earliest failure").isEqualTo(8));
        assertThat(seen).containsExactlyInAnyOrder("a0", "d0", "c0", "b0");
    }

    @Test
    void aParkTheBrokerRefusesIsRedeliveredNotSwallowed() {
        doAnswer(invocation -> {
            throw new IllegalStateException("defective forever");
        }).when(upstream).handle(any());
        org.mockito.Mockito.doThrow(new IllegalStateException("dead-letter refused"))
                .when(deadLetters).park(any(), any(), any());

        assertThatThrownBy(() -> config(1).consumeUpstream().accept(batch(List.of(event("A", "a0")), 0)))
                .isInstanceOf(BatchListenerFailedException.class)
                .hasRootCauseMessage("dead-letter refused");
    }

    @Test
    void withoutPositionHeadersTheFailureFallsBackToThePayloadIndex() {
        doAnswer(invocation -> {
            var key = invocation.getArgument(0, ProcessUpstreamEventCommand.class).event().partitionKey();
            if ("B".equals(key)) {
                throw new RecoverableDataAccessException("db blipped");
            }
            return null;
        }).when(upstream).handle(any());

        assertThatThrownBy(() -> config(1).consumeUpstream().accept(org.springframework.messaging.support.MessageBuilder
                        .withPayload(List.of(event("A", "a0"), event("B", "b0"))).build()))
                .isInstanceOfSatisfying(BatchListenerFailedException.class, e -> {
                    assertThat(e.getRecord()).isNull();
                    assertThat(e.getIndex()).isEqualTo(1);
                });
    }
}
