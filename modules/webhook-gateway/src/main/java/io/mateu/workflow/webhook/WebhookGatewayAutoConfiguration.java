package io.mateu.workflow.webhook;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.http.HttpClient;
import java.util.EnumMap;
import java.util.Map;

/**
 * Wires the gateway wherever this module is on the classpath of a web application: the controller,
 * the providers, and the publisher the transport asks for — Kafka (a producer of its own, bytes in and
 * out, on {@code workflow.webhooks.kafka.bootstrap-servers} or {@code spring.kafka.bootstrap-servers})
 * or the engine's REST inject. {@code workflow.webhooks.enabled=false} leaves it all out.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "workflow.webhooks.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({WebhookGatewayProperties.class, WebhookDestinationsProperties.class})
public class WebhookGatewayAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Map<WebhookGatewayProperties.Provider, WebhookProvider> webhookProviders() {
        var providers = new EnumMap<WebhookGatewayProperties.Provider, WebhookProvider>(WebhookGatewayProperties.Provider.class);
        providers.put(WebhookGatewayProperties.Provider.GITHUB, new GitHubWebhookProvider());
        providers.put(WebhookGatewayProperties.Provider.GENERIC, new GenericWebhookProvider());
        return providers;
    }

    @Bean
    @ConditionalOnMissingBean
    public WebhookMetrics webhookMetrics(ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry) {
        try {
            return (endpoint, outcome) -> {
                var meters = registry.getIfAvailable();
                if (meters == null) return;
                meters.counter("eventconductor.webhooks.received", "endpoint", endpoint, "outcome", outcome).increment();
            };
        } catch (NoClassDefFoundError noMicrometer) {
            return WebhookMetrics.NONE;
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public WebhookController webhookController(WebhookGatewayProperties properties,
                                               Map<WebhookGatewayProperties.Provider, WebhookProvider> providers,
                                               WebhookPublisher publisher, WebhookMetrics metrics) {
        return new WebhookController(properties, providers, publisher, metrics);
    }

    /** Caps the body of a webhook request before it is read whole ({@code workflow.webhooks.max-body-bytes}). */
    @Bean
    @ConditionalOnMissingBean
    public WebhookBodyLimitFilter webhookBodyLimitFilter(WebhookGatewayProperties properties) {
        return new WebhookBodyLimitFilter(properties);
    }

    /** The REST transport: the engine's inject endpoint, over the JDK's HttpClient. */
    @Bean
    @ConditionalOnMissingBean(WebhookPublisher.class)
    @ConditionalOnProperty(name = "workflow.webhooks.transport", havingValue = "rest")
    public WebhookPublisher restWebhookPublisher(WebhookGatewayProperties properties) {
        return new RestWebhookPublisher(HttpClient.newBuilder().connectTimeout(properties.getEngine().getTimeout()).build(),
                properties.getEngine());
    }

    /** The Kafka transport (the default), when spring-kafka is on the classpath. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
    @ConditionalOnProperty(name = "workflow.webhooks.transport", havingValue = "kafka", matchIfMissing = true)
    static class KafkaTransport {

        @Bean
        @ConditionalOnMissingBean(WebhookPublisher.class)
        public WebhookPublisher kafkaWebhookPublisher(WebhookGatewayProperties properties,
                                                      WebhookDestinationsProperties destinations, Environment environment) {
            var brokers = properties.getKafka().getBootstrapServers();
            if (brokers == null || brokers.isBlank()) {
                brokers = environment.getProperty("spring.kafka.bootstrap-servers", "localhost:9092");
            }
            var config = new java.util.HashMap<String, Object>();
            config.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
            config.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                    org.apache.kafka.common.serialization.ByteArraySerializer.class);
            config.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                    org.apache.kafka.common.serialization.ByteArraySerializer.class);
            config.put(org.apache.kafka.clients.producer.ProducerConfig.ACKS_CONFIG, "all");
            config.put(org.apache.kafka.clients.producer.ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
            var factory = new org.springframework.kafka.core.DefaultKafkaProducerFactory<byte[], byte[]>(config);
            return new KafkaWebhookPublisher(new org.springframework.kafka.core.KafkaTemplate<>(factory), destinations,
                    properties.getKafka().getSendTimeout());
        }
    }
}
