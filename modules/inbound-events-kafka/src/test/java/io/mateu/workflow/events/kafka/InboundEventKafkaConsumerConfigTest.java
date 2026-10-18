package io.mateu.workflow.events.kafka;

import io.mateu.workflow.application.services.EventTriggerService;
import io.mateu.workflow.application.services.InboundEventService;
import io.mateu.workflow.infra.config.EventDestinationsProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The inbound consumer inherits the application's Kafka security and TLS settings — Spring Kafka's,
 * then the binder's, then its own — while what it needs to work stays its own.
 */
class InboundEventKafkaConsumerConfigTest {

    final EventDestinationsProperties properties = new EventDestinationsProperties();
    final MockEnvironment environment = new MockEnvironment();
    final InboundEventKafkaConsumer consumer = new InboundEventKafkaConsumer(mock(InboundEventService.class),
            mock(EventTriggerService.class), properties, environment);

    /** What the consumer factory the listener container is built on was configured with. */
    private Map<String, Object> factoryConfig() {
        return consumer.consumerFactory().getConfigurationProperties();
    }

    @Test
    void springKafkaSecurityProtocolAndSaslPropertiesReachTheConsumer() {
        environment.setProperty("spring.kafka.security.protocol", "SASL_SSL");
        environment.setProperty("spring.kafka.properties.sasl.mechanism", "SCRAM-SHA-512");
        environment.setProperty("spring.kafka.properties.sasl.jaas.config", "ScramLoginModule required;");

        assertThat(factoryConfig()).containsEntry("security.protocol", "SASL_SSL")
                .containsEntry("sasl.mechanism", "SCRAM-SHA-512")
                .containsEntry("sasl.jaas.config", "ScramLoginModule required;");
    }

    @Test
    void springKafkaSslReachesTheConsumerFromTheApplicationsKafkaProperties(@TempDir Path dir) throws Exception {
        var trustStore = Files.createFile(dir.resolve("truststore.p12"));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class,
                        InboundEventKafkaAutoConfiguration.class))
                .withBean(EventDestinationsProperties.class, EventDestinationsProperties::new)
                .withBean(InboundEventService.class, () -> mock(InboundEventService.class))
                .withBean(EventTriggerService.class, () -> mock(EventTriggerService.class))
                .withPropertyValues("workflow.mode=kafka",
                        "spring.kafka.security.protocol=SSL",
                        "spring.kafka.ssl.trust-store-location=" + trustStore.toUri(),
                        "spring.kafka.ssl.trust-store-password=changeit",
                        "spring.kafka.ssl.trust-store-type=PKCS12")
                .run(context -> assertThat(context.getBean(InboundEventKafkaConsumer.class).consumerFactory()
                        .getConfigurationProperties())
                        .containsEntry("security.protocol", "SSL")
                        .containsEntry("ssl.truststore.location", trustStore.toAbsolutePath().toString())
                        .containsEntry("ssl.truststore.password", "changeit")
                        .containsEntry("ssl.truststore.type", "PKCS12"));
    }

    @Test
    void theBindersConfigurationOverridesSpringKafkaAndTheConsumersOwnOverridesBoth() {
        environment.setProperty("spring.kafka.security.protocol", "SSL");
        environment.setProperty("spring.kafka.properties.sasl.mechanism", "PLAIN");
        environment.setProperty("spring.kafka.properties.sasl.jaas.config", "spring");
        environment.setProperty("spring.cloud.stream.kafka.binder.configuration.security.protocol", "SASL_SSL");
        environment.setProperty("spring.cloud.stream.kafka.binder.configuration.sasl.jaas.config", "binder");
        environment.setProperty("spring.cloud.stream.kafka.binder.consumer-properties.max.poll.records", "50");
        properties.getConsumer().getProperties().put("sasl.jaas.config", "own");

        assertThat(factoryConfig()).containsEntry("security.protocol", "SASL_SSL")
                .containsEntry("sasl.mechanism", "PLAIN")
                .containsEntry("max.poll.records", "50")
                .containsEntry("sasl.jaas.config", "own");
    }

    @Test
    void whatTheConsumerNeedsToWorkCannotBeOverridden() {
        environment.setProperty("spring.kafka.consumer.group-id", "theirs");
        environment.setProperty("spring.kafka.consumer.enable-auto-commit", "true");
        environment.setProperty("spring.kafka.consumer.auto-offset-reset", "earliest");
        environment.setProperty("spring.cloud.stream.kafka.binder.configuration.key.deserializer",
                "org.apache.kafka.common.serialization.StringDeserializer");
        properties.getConsumer().setBootstrapServers("broker:9092");
        properties.getConsumer().getProperties().putAll(Map.of(
                "bootstrap.servers", "elsewhere:1",
                "group.id", "mine",
                "enable.auto.commit", "true",
                "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"));

        assertThat(factoryConfig()).containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker:9092")
                .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "eventconductor-inbound-events")
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class)
                .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    }
}
