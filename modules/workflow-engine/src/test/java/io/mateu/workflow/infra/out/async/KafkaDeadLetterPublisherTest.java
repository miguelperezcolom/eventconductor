package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.ddd.DomainEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.util.MimeTypeUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * What a dead letter looks like on the wire. The body has to stay the original, replayable as it is;
 * everything about the failure travels in headers; and the record keeps its process's partition key,
 * so a replay reaches the pod that owns the process.
 */
class KafkaDeadLetterPublisherTest {

    private final StreamBridge streamBridge = mock(StreamBridge.class);
    private final WorkflowMetrics metrics = mock(WorkflowMetrics.class);
    private final KafkaDeadLetterPublisher publisher = new KafkaDeadLetterPublisher(streamBridge, metrics);

    record Keyed(String processId) implements DomainEvent {
        @Override
        public String partitionKey() {
            return processId;
        }
    }

    record Unkeyed(String payload) implements DomainEvent {
    }

    private Message<?> sent() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Message<?>> captor = (ArgumentCaptor) ArgumentCaptor.forClass(Message.class);
        verify(streamBridge).send(eq(KafkaDeadLetterPublisher.BINDING), captor.capture());
        return captor.getValue();
    }

    @Test
    void anEventIsParkedUntouchedWithTheFailureInHeadersAndItsProcessKey() {
        var event = new Keyed("process-7");

        publisher.park(event, new IllegalStateException("step not found"), "upstream");

        var message = sent();
        assertThat(KafkaDeadLetterPublisher.BINDING).isEqualTo("deadLetter");
        assertThat(message.getPayload()).isSameAs(event);
        var headers = message.getHeaders();
        assertThat(headers.get("x-dead-letter-source")).isEqualTo("upstream");
        assertThat(headers.get("x-dead-letter-reason")).isEqualTo(IllegalStateException.class.getName());
        assertThat(headers.get("x-dead-letter-message")).isEqualTo("step not found");
        assertThat(headers.get("x-dead-letter-process")).isEqualTo("process-7");
        assertThat(headers.get(KafkaHeaders.KEY)).isEqualTo("process-7".getBytes(StandardCharsets.UTF_8));
        assertThat(headers).doesNotContainKey("x-dead-letter-unreadable");
        verify(metrics).eventDeadLettered("upstream");
    }

    @Test
    void anEventWithoutAProcessGoesWithoutAKey() {
        publisher.park(new Unkeyed("x"), new RuntimeException(), "outbox");

        var headers = sent().getHeaders();
        assertThat(headers).doesNotContainKey(KafkaHeaders.KEY);
        assertThat(headers.get("x-dead-letter-process")).isEqualTo("null");
        // A cause with no message still says so, rather than dropping the header.
        assertThat(headers.get("x-dead-letter-message")).isEqualTo("null");
        verify(metrics).eventDeadLettered("outbox");
    }

    @Test
    void aBlankProcessKeyIsNotAKey() {
        publisher.park(new Keyed("  "), new RuntimeException("boom"), "upstream");

        assertThat(sent().getHeaders()).doesNotContainKey(KafkaHeaders.KEY);
    }

    @Test
    void anUnreadableRecordIsParkedAsTheOriginalBytes() {
        var bytes = "{not json".getBytes(StandardCharsets.UTF_8);

        publisher.parkUnreadable(bytes, new IllegalArgumentException("Unexpected character"), "upstream");

        var message = sent();
        assertThat(message.getPayload()).isSameAs(bytes);
        var headers = message.getHeaders();
        // Octet stream, or the JSON converter would re-encode the bytes and the letter would not replay.
        assertThat(headers.get(MessageHeaders.CONTENT_TYPE)).isEqualTo(MimeTypeUtils.APPLICATION_OCTET_STREAM_VALUE);
        assertThat(headers.get("x-dead-letter-source")).isEqualTo("upstream");
        assertThat(headers.get("x-dead-letter-reason")).isEqualTo(IllegalArgumentException.class.getName());
        assertThat(headers.get("x-dead-letter-message")).isEqualTo("Unexpected character");
        assertThat(headers.get("x-dead-letter-unreadable")).isEqualTo("true");
        // Nobody could parse it, so it belongs to no process: no key, no process header.
        assertThat(headers).doesNotContainKeys(KafkaHeaders.KEY, "x-dead-letter-process");
        verify(metrics).eventDeadLettered("upstream");
    }

    @Test
    void anUnreadableRecordWithNoCauseStillSaysWhy() {
        publisher.parkUnreadable(new byte[]{1, 2, 3}, null, "outbox");

        var headers = sent().getHeaders();
        assertThat(headers.get("x-dead-letter-reason")).isEqualTo("unreadable");
        assertThat(headers.get("x-dead-letter-message")).isEqualTo("the record could not be converted to an event");
        verify(metrics).eventDeadLettered("outbox");
        verify(streamBridge).send(eq("deadLetter"), any(Message.class));
    }
}
