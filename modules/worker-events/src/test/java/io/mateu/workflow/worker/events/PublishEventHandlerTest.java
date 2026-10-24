package io.mateu.workflow.worker.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;
import io.mateu.workflow.worker.api.TaskFailure;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublishEventHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ExternalEventRequested EVENT = new ExternalEventRequested("se-1", "orders",
            "com.acme.order.placed", "O-1", null, "{\"orderId\":\"O-1\"}", "p-1", "wd", "publish", "bk-1",
            "2026-10-24T10:00:00Z");

    private static PublishEventHandler.Input input(Object value) throws Exception {
        return new PublishEventHandler.Input(JSON.valueToTree(value));
    }

    @Test
    void sendsTheRenderedEventAndCompletesWithNoVariables() throws Exception {
        var sent = new ArrayList<ExternalEventRequested>();
        var handler = new PublishEventHandler((event, trace) -> sent.add(event));

        var output = handler.handle(input(EVENT), null);

        assertThat(output).isEmpty();
        assertThat(sent).containsExactly(EVENT);
    }

    @Test
    void readsTheEventWhenItArrivesAsJsonText() throws Exception {
        var sent = new ArrayList<ExternalEventRequested>();
        new PublishEventHandler((event, trace) -> sent.add(event)).handle(input(JSON.writeValueAsString(EVENT)), null);
        assertThat(sent).containsExactly(EVENT);
    }

    @Test
    void ignoresDerivedPropertiesTheEngineSerialisedAlongTheEvent() throws Exception {
        var tree = JSON.valueToTree(EVENT);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).put("partitionKey", "O-1");
        var sent = new ArrayList<ExternalEventRequested>();
        new PublishEventHandler((event, trace) -> sent.add(event)).handle(new PublishEventHandler.Input(tree), null);
        assertThat(sent).containsExactly(EVENT);
    }

    @Test
    void aTaskWithoutAnEventFailsForGood() {
        var handler = new PublishEventHandler((event, trace) -> { });
        assertThatThrownBy(() -> handler.handle(new PublishEventHandler.Input(null), null))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> assertThat(failure.code()).isEqualTo("EVENT_REQUEST"));
        assertThatThrownBy(() -> handler.handle(input(java.util.Map.of("eventId", "x")), null))
                .isInstanceOf(TaskFailure.class);
        assertThatThrownBy(() -> handler.handle(input("not json"), null))
                .isInstanceOf(TaskFailure.class);
    }

    @Test
    void noSinkFailsForGood() {
        var handler = new PublishEventHandler(null);
        assertThatThrownBy(() -> handler.handle(input(EVENT), null))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> assertThat(failure.code()).isEqualTo("EVENT_SINK"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void kafkaSendsACloudEventToTheDestinationsTopicKeyedByTheEventsKey() {
        var bridge = mock(StreamBridge.class);
        var messages = new ArrayList<Message<byte[]>>();
        when(bridge.send(eq("orders-events"), any(Object.class))).thenAnswer(call -> {
            messages.add((Message<byte[]>) call.getArgument(1));
            return true;
        });
        var properties = new EventsWorkerProperties();
        var destination = new EventsWorkerProperties.Destination();
        destination.setTopic("orders-events");
        properties.getDestinations().put("orders", destination);

        new KafkaEventSink(bridge, properties).send(EVENT, null);

        var message = messages.getFirst();
        assertThat(new String(message.getPayload(), StandardCharsets.UTF_8)).isEqualTo("{\"orderId\":\"O-1\"}");
        assertThat(message.getHeaders().get("ce_id")).isEqualTo("se-1");
        assertThat(message.getHeaders().get("ce_type")).isEqualTo("com.acme.order.placed");
        assertThat(new String((byte[]) message.getHeaders().get("kafka_messageKey"), StandardCharsets.UTF_8)).isEqualTo("O-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void kafkaCarriesTheTasksTraceOnTheEvent() throws Exception {
        var bridge = mock(StreamBridge.class);
        var messages = new ArrayList<Message<byte[]>>();
        when(bridge.send(eq("orders-events"), any(Object.class))).thenAnswer(call -> {
            messages.add((Message<byte[]>) call.getArgument(1));
            return true;
        });
        var properties = new EventsWorkerProperties();
        var destination = new EventsWorkerProperties.Destination();
        destination.setTopic("orders-events");
        properties.getDestinations().put("orders", destination);
        var traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        var context = mock(io.mateu.workflow.worker.api.TaskContext.class);
        when(context.traceContext()).thenReturn(io.mateu.workflow.dtos.TraceContext.of(traceparent));

        new PublishEventHandler(new KafkaEventSink(bridge, properties)).handle(input(EVENT), context);

        assertThat(new String((byte[]) messages.getFirst().getHeaders().get("traceparent"), StandardCharsets.UTF_8))
                .isEqualTo(traceparent);
        assertThat(messages.getFirst().getHeaders().get("ce_id")).isEqualTo("se-1");
    }

    @Test
    void kafkaFailsForGoodOnAnUnconfiguredDestinationAndRetryablyOnARefusedSend() {
        var bridge = mock(StreamBridge.class);
        when(bridge.send(any(String.class), any(Object.class))).thenReturn(false);
        var properties = new EventsWorkerProperties();
        var sink = new KafkaEventSink(bridge, properties);
        assertThatThrownBy(() -> sink.send(EVENT, null))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("EVENT_DESTINATION");
                    assertThat(failure.retryable()).isFalse();
                });

        var destination = new EventsWorkerProperties.Destination();
        destination.setTopic("orders-events");
        properties.getDestinations().put("orders", destination);
        assertThatThrownBy(() -> sink.send(EVENT, null))
                .isInstanceOfSatisfying(TaskFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("EVENT_IO");
                    assertThat(failure.retryable()).isTrue();
                });
    }

    @Test
    void registersThePublishEventTaskOnItsTopic() {
        var properties = new EventsWorkerProperties();
        var configuration = new EventsWorkerAutoConfiguration();
        var registration = configuration.publishEventTaskRegistration(new PublishEventHandler((event, trace) -> { }), properties);
        assertThat(registration.ref()).isEqualTo("publish-event@1");
        assertThat(registration.topic()).isEqualTo("event-publications");
        assertThat(List.of(registration.inputType())).containsExactly(PublishEventHandler.Input.class);
    }
}
