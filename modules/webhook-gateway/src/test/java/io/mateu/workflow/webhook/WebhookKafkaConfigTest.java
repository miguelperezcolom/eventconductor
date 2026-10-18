package io.mateu.workflow.webhook;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway's producer inherits the application's Kafka security and TLS settings — Spring Kafka's,
 * then the binder's, then its own — while what it needs to work stays its own.
 */
class WebhookKafkaConfigTest {

    final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebhookGatewayAutoConfiguration.class))
            .withPropertyValues("workflow.webhooks.kafka.bootstrap-servers=broker:9092");

    /** The configuration the publisher's producer factory was built with. */
    @SuppressWarnings("unchecked")
    private void producerConfig(WebApplicationContextRunner runner, Consumer<Map<String, Object>> assertions) {
        runner.run(context -> {
            var template = (KafkaTemplate<byte[], byte[]>) ReflectionTestUtils.getField(
                    context.getBean(WebhookPublisher.class), "template");
            assertions.accept(template.getProducerFactory().getConfigurationProperties());
        });
    }

    @Test
    void springKafkaSecurityProtocolAndSaslPropertiesReachTheProducer() {
        producerConfig(runner.withPropertyValues(
                "spring.kafka.security.protocol=SASL_SSL",
                "spring.kafka.properties.sasl.mechanism=SCRAM-SHA-512",
                "spring.kafka.properties.sasl.jaas.config=ScramLoginModule required;"), config -> {
            assertThat(config).containsEntry("security.protocol", "SASL_SSL")
                    .containsEntry("sasl.mechanism", "SCRAM-SHA-512")
                    .containsEntry("sasl.jaas.config", "ScramLoginModule required;");
        });
    }

    @Test
    void springKafkaSslReachesTheProducerFromTheApplicationsKafkaProperties(@TempDir Path dir) throws Exception {
        var trustStore = Files.createFile(dir.resolve("truststore.p12"));
        producerConfig(runner.withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
                .withPropertyValues(
                        "spring.kafka.security.protocol=SSL",
                        "spring.kafka.ssl.trust-store-location=" + trustStore.toUri(),
                        "spring.kafka.ssl.trust-store-password=changeit",
                        "spring.kafka.ssl.trust-store-type=PKCS12"), config -> {
            assertThat(config).containsEntry("security.protocol", "SSL")
                    .containsEntry("ssl.truststore.location", trustStore.toAbsolutePath().toString())
                    .containsEntry("ssl.truststore.password", "changeit")
                    .containsEntry("ssl.truststore.type", "PKCS12");
        });
    }

    @Test
    void theBindersConfigurationOverridesSpringKafkaAndTheGatewaysOwnOverridesBoth() {
        producerConfig(runner.withPropertyValues(
                "spring.kafka.security.protocol=SSL",
                "spring.kafka.properties.sasl.mechanism=PLAIN",
                "spring.kafka.properties.sasl.jaas.config=spring",
                "spring.cloud.stream.kafka.binder.configuration.security.protocol=SASL_SSL",
                "spring.cloud.stream.kafka.binder.configuration.sasl.jaas.config=binder",
                "spring.cloud.stream.kafka.binder.producer-properties.linger.ms=5",
                "workflow.webhooks.kafka.properties.sasl.jaas.config=own"), config -> {
            assertThat(config).containsEntry("security.protocol", "SASL_SSL")
                    .containsEntry("sasl.mechanism", "PLAIN")
                    .containsEntry("linger.ms", "5")
                    .containsEntry("sasl.jaas.config", "own");
        });
    }

    @Test
    void whatTheProducerNeedsToWorkCannotBeOverridden() {
        producerConfig(runner.withPropertyValues(
                "spring.kafka.producer.acks=1",
                "spring.kafka.producer.transaction-id-prefix=tx-",
                "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "spring.cloud.stream.kafka.binder.configuration.enable.idempotence=false",
                "workflow.webhooks.kafka.properties.bootstrap.servers=elsewhere:1",
                "workflow.webhooks.kafka.properties.acks=0",
                "workflow.webhooks.kafka.properties.value.serializer=org.apache.kafka.common.serialization.StringSerializer"),
                config -> {
            assertThat(config).containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker:9092")
                    .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                    .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                    .containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class)
                    .containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class)
                    .doesNotContainKey(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
        });
    }

    @Test
    void theBrokersAreTheGatewaysOwnElseTheBindersElseSpringKafkas() {
        var properties = new WebhookGatewayProperties();
        var environment = new MockEnvironment();
        assertThat(WebhookKafkaConfig.bootstrapServers(properties, environment)).isEqualTo("localhost:9092");
        environment.setProperty("spring.kafka.bootstrap-servers", "k:1");
        assertThat(WebhookKafkaConfig.bootstrapServers(properties, environment)).isEqualTo("k:1");
        environment.setProperty("spring.cloud.stream.kafka.binder.brokers", "b:2");
        assertThat(WebhookKafkaConfig.bootstrapServers(properties, environment)).isEqualTo("b:2");
        properties.getKafka().setBootstrapServers("own:3");
        assertThat(WebhookKafkaConfig.bootstrapServers(properties, environment)).isEqualTo("own:3");
    }

    @Test
    void withoutSpringBootsKafkaSupportSpringKafkaPropertiesAreStillInherited() {
        var environment = new MockEnvironment()
                .withProperty("spring.kafka.properties.security.protocol", "SASL_SSL")
                .withProperty("spring.kafka.properties.sasl.mechanism", "PLAIN");
        assertThat(WebhookKafkaConfig.springKafka(environment, null, false))
                .containsExactlyInAnyOrderEntriesOf(Map.of("security.protocol", "SASL_SSL", "sasl.mechanism", "PLAIN"));
        // With it, but no KafkaProperties bean: bound from the environment.
        assertThat(WebhookKafkaConfig.springKafka(environment, null, true))
                .containsEntry("security.protocol", "SASL_SSL");
    }
}
