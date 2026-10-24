package io.mateu.workflow.worker.events;

import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;
import io.mateu.workflow.infra.out.events.PublishedEventDelivery;
import io.mateu.workflow.worker.api.TaskRegistration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.stream.function.StreamBridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EventsWorkerAutoConfigurationTest {

    private static final ExternalEventRequested EVENT = new ExternalEventRequested("se-1", "orders",
            "com.acme.order.placed", "O-1", null, "{}", "p-1", "wd", "publish", null, "2026-10-24T10:00:00Z");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EventsWorkerAutoConfiguration.class));

    @Test
    void kafkaModeSendsToTheDestinationsTopic() {
        runner.withPropertyValues("workflow.mode=kafka", "workflow.events.destinations.orders.topic=orders-events",
                        "workflow.events.destinations.orders.format=plain", "workflow.events.worker.topic=custom")
                .withBean(StreamBridge.class, () -> mock(StreamBridge.class))
                .run(context -> {
                    assertThat(context.getBean(EventSink.class)).isInstanceOf(KafkaEventSink.class);
                    var properties = context.getBean(EventsWorkerProperties.class);
                    assertThat(properties.getDestinations().get("orders").getTopic()).isEqualTo("orders-events");
                    assertThat(properties.getDestinations().get("orders").getFormat()).isEqualTo("plain");
                    assertThat(properties.getWorker().isEnabled()).isTrue();
                    assertThat(context.getBean(TaskRegistration.class).topic()).isEqualTo("custom");
                });
    }

    @Test
    void embeddedModeHandsTheEventToTheEnginesDelivery() {
        var delivery = mock(PublishedEventDelivery.class);
        runner.withBean(PublishedEventDelivery.class, () -> delivery)
                .run(context -> {
                    context.getBean(PublishEventHandler.class);
                    context.getBean(EventSink.class).send(EVENT, null);
                    verify(delivery).deliver(EVENT);
                });
    }

    @Test
    void disabledRegistersNoTask() {
        runner.withPropertyValues("workflow.events.worker.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(TaskRegistration.class)
                        .doesNotHaveBean(PublishEventHandler.class));
    }

    @Test
    void propertiesCanBeReplacedWholesale() {
        var properties = new EventsWorkerProperties();
        var worker = new EventsWorkerProperties.Worker();
        worker.setEnabled(false);
        worker.setTopic("t");
        properties.setWorker(worker);
        properties.setDestinations(new java.util.LinkedHashMap<>());
        assertThat(properties.getWorker().getTopic()).isEqualTo("t");
        assertThat(properties.getWorker().isEnabled()).isFalse();
        assertThat(properties.getDestinations()).isEmpty();
    }
}
