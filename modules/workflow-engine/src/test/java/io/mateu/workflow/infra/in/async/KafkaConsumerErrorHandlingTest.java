package io.mateu.workflow.infra.in.async;

import io.mateu.workflow.application.out.ConcurrentProcessAccessException;
import io.mateu.workflow.application.out.DeadLetterPublisher;
import io.mateu.workflow.application.out.PoisonEventException;
import io.mateu.workflow.application.out.WorkflowMetrics;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The engine consumers' error handler, driven the way the container drives it: a batch fails with a
 * {@link BatchListenerFailedException} naming a record, and the handler decides between redelivering
 * (seek, nothing committed from that record on) and recovering (park, commit). The one outcome it must
 * never produce for a retryable failure is the third one Spring Kafka's default had — giving up.
 */
class KafkaConsumerErrorHandlingTest {

    private final DeadLetterPublisher deadLetters = mock(DeadLetterPublisher.class);
    private final WorkflowMetrics metrics = mock(WorkflowMetrics.class);

    private DefaultErrorHandler handler(long maxAttempts) {
        return (DefaultErrorHandler) new KafkaConsumerErrorHandling().eventconductorEngineErrorHandler(
                deadLetters, metrics, Duration.ZERO, 2.0, Duration.ZERO, maxAttempts, 10);
    }

    private static ConsumerRecords<byte[], byte[]> batch() {
        var partition = new TopicPartition("outbox", 0);
        return new ConsumerRecords<>(Map.of(partition, List.of(
                new ConsumerRecord<>("outbox", 0, 10L, null, "{\"a\":0}".getBytes(StandardCharsets.UTF_8)),
                new ConsumerRecord<>("outbox", 0, 11L, null, "{\"b\":0}".getBytes(StandardCharsets.UTF_8)),
                new ConsumerRecord<>("outbox", 0, 12L, null, "{\"c\":0}".getBytes(StandardCharsets.UTF_8)))), Map.of());
    }

    private static MessageListenerContainer container() {
        var container = mock(MessageListenerContainer.class);
        when(container.getContainerProperties()).thenReturn(new ContainerProperties("outbox"));
        when(container.isRunning()).thenReturn(true);
        return container;
    }

    private static BatchListenerFailedException failedAt(long offset, RuntimeException cause) {
        return new BatchListenerFailedException("failed", cause,
                new ConsumerRecord<>("outbox", 0, offset, null, null));
    }

    @Test
    void aRetryableFailureIsRedeliveredFarBeyondTheOldTenAttemptsAndNeverParked() {
        var handler = handler(0);
        var consumer = mock(Consumer.class);
        var container = container();
        var conflict = new ObjectOptimisticLockingFailureException("process", "p1",
                new ConcurrentProcessAccessException("p1", null));

        for (int attempt = 1; attempt <= 50; attempt++) {
            assertThatThrownBy(() -> handler.handleBatch(failedAt(11, conflict), batch(), consumer, container, () -> { }))
                    .as("attempt %s re-seeks rather than gives up", attempt)
                    .isNotNull();
        }

        // Record 10 committed (it is before the failed one), the failed one sought back every time.
        verify(consumer, atLeastOnce()).commitSync(eq(Map.of(new TopicPartition("outbox", 0),
                new org.apache.kafka.clients.consumer.OffsetAndMetadata(11L))), any());
        verify(consumer, atLeastOnce()).seek(new TopicPartition("outbox", 0), 11L);
        verify(deadLetters, never()).parkUnreadable(any(), any(), any());
        verify(metrics, atLeastOnce()).eventRedelivered(eq("outbox"), any(), anyInt());
    }

    @Test
    void aPoisonFailureIsParkedOnTheDeadLetterTopicAndCommitted() {
        var handler = handler(0);
        var consumer = mock(Consumer.class);

        try {
            handler.handleBatch(failedAt(11, new PoisonEventException("no such step")), batch(), consumer, container(),
                    () -> { });
        } catch (RuntimeException theRecordAfterItIsSoughtAgain) {
            // record 12 is re-sought so it is delivered again; expected
        }
        // Committed through the parked record: it is not redelivered.
        verify(consumer, atLeastOnce()).commitSync(eq(Map.of(new TopicPartition("outbox", 0),
                new org.apache.kafka.clients.consumer.OffsetAndMetadata(12L))), any());

        verify(deadLetters).parkUnreadable(eq("{\"b\":0}".getBytes(StandardCharsets.UTF_8)),
                any(PoisonEventException.class), eq("outbox"));
    }

    @Test
    void aParkTheBrokerRefusesLeavesTheRecordToBeRetried() {
        var handler = handler(0);
        var consumer = mock(Consumer.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
                .when(deadLetters).parkUnreadable(any(), any(), any());

        assertThatThrownBy(() -> handler.handleBatch(failedAt(11, new PoisonEventException("no such step")), batch(),
                consumer, container(), () -> { })).isNotNull();

        verify(consumer, atLeastOnce()).seek(new TopicPartition("outbox", 0), 11L);
        verify(consumer, never()).commitSync(eq(Map.of(new TopicPartition("outbox", 0),
                new org.apache.kafka.clients.consumer.OffsetAndMetadata(12L))), any());
    }

    @Test
    void anOperatorBoundIsHonouredAndEndsInTheDeadLetterTopicNotADiscard() {
        var handler = handler(3);
        var consumer = mock(Consumer.class);
        var container = container();
        var conflict = new ConcurrentProcessAccessException("p1", null);

        for (int attempt = 1; attempt < 3; attempt++) {
            assertThatThrownBy(() -> handler.handleBatch(failedAt(10, conflict), batch(), consumer, container, () -> { }))
                    .isNotNull();
        }
        verify(deadLetters, never()).parkUnreadable(any(), any(), any());
        try {
            handler.handleBatch(failedAt(10, conflict), batch(), consumer, container, () -> { });
        } catch (RuntimeException remainingRecordsAreSoughtAgain) {
            // the records after the recovered one are re-sought; that is expected
        }
        verify(deadLetters).parkUnreadable(any(), any(), eq("outbox"));
    }
}
