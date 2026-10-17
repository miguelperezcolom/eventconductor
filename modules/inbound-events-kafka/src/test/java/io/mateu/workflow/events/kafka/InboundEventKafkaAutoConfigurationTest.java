package io.mateu.workflow.events.kafka;

import io.mateu.workflow.application.services.EventTriggerService;
import io.mateu.workflow.application.services.InboundEventService;
import io.mateu.workflow.infra.config.EventDestinationsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The consumer is wired only in kafka mode, and only where the engine's event services exist. */
class InboundEventKafkaAutoConfigurationTest {

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(InboundEventKafkaAutoConfiguration.class))
            .withBean(EventDestinationsProperties.class, EventDestinationsProperties::new);

    @Test
    void inKafkaModeWithTheEngineTheConsumerIsWired() {
        runner.withPropertyValues("workflow.mode=kafka")
                .withBean(InboundEventService.class, () -> mock(InboundEventService.class))
                .withBean(EventTriggerService.class, () -> mock(EventTriggerService.class))
                .run(context -> assertThat(context).hasSingleBean(InboundEventKafkaConsumer.class));
    }

    @Test
    void outsideKafkaModeNothingIsWired() {
        runner.withPropertyValues("workflow.mode=embedded")
                .withBean(InboundEventService.class, () -> mock(InboundEventService.class))
                .withBean(EventTriggerService.class, () -> mock(EventTriggerService.class))
                .run(context -> assertThat(context).doesNotHaveBean(InboundEventKafkaConsumer.class));
    }

    @Test
    void withoutTheEngineEventServicesNothingIsWired() {
        runner.withPropertyValues("workflow.mode=kafka")
                .run(context -> assertThat(context).doesNotHaveBean(InboundEventKafkaConsumer.class));
    }
}
