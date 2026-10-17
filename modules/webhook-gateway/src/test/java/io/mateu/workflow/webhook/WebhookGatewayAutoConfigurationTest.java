package io.mateu.workflow.webhook;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** What the gateway wires in a web application, by transport, and its off switch. */
class WebhookGatewayAutoConfigurationTest {

    final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebhookGatewayAutoConfiguration.class));

    @Test
    void byDefaultEventsGoOverKafka() {
        runner.withPropertyValues("workflow.webhooks.kafka.bootstrap-servers=broker:9092").run(context -> {
            assertThat(context).hasSingleBean(WebhookController.class);
            assertThat(context).hasSingleBean(WebhookBodyLimitFilter.class);
            assertThat(context.getBean(WebhookPublisher.class)).isInstanceOf(KafkaWebhookPublisher.class);
        });
        // Without brokers of its own it uses Spring Kafka's.
        runner.withPropertyValues("spring.kafka.bootstrap-servers=k:1")
                .run(context -> assertThat(context.getBean(WebhookPublisher.class))
                        .isInstanceOf(KafkaWebhookPublisher.class));
    }

    @Test
    void theRestTransportHandsEventsToTheEngine() {
        runner.withPropertyValues("workflow.webhooks.transport=rest", "workflow.webhooks.engine.url=http://engine")
                .run(context -> assertThat(context.getBean(WebhookPublisher.class))
                        .isInstanceOf(RestWebhookPublisher.class));
    }

    @Test
    void disabledNothingIsWired() {
        runner.withPropertyValues("workflow.webhooks.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(WebhookController.class));
    }

    @Test
    void deliveriesAreCountedPerEndpointAndOutcomeWhenThereIsARegistry() {
        var registry = new SimpleMeterRegistry();
        runner.withBean(MeterRegistry.class, () -> registry).run(context -> {
            context.getBean(WebhookMetrics.class).count("github", "published");
            context.getBean(WebhookMetrics.class).count("github", "published");
            assertThat(registry.get("eventconductor.webhooks.received").tag("endpoint", "github")
                    .tag("outcome", "published").counter().count()).isEqualTo(2.0);
        });
        // Without one, counting is a no-op rather than a failure.
        runner.run(context -> context.getBean(WebhookMetrics.class).count("github", "published"));
    }
}
