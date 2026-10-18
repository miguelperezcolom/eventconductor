package io.mateu.workflow.webhook;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * The configuration of the gateway's Kafka producer. It inherits the application's Kafka client
 * settings — authentication and TLS included — so it connects to the cluster the way the engine's
 * binder does. Lowest to highest precedence:
 * <ol>
 *   <li>Spring Kafka ({@code spring.kafka.*}: {@code security.*}, {@code ssl.*}, {@code properties.*},
 *       {@code producer.*}), as {@code KafkaProperties.buildProducerProperties()} builds them — or, without
 *       Spring Boot's Kafka support on the classpath, just {@code spring.kafka.properties.*};</li>
 *   <li>the binder's {@code spring.cloud.stream.kafka.binder.configuration.*}, then its
 *       {@code producer-properties.*};</li>
 *   <li>{@code workflow.webhooks.kafka.properties.*};</li>
 *   <li>what the producer needs to work — brokers, byte-array serializers, {@code acks=all}, idempotence,
 *       no transactions — which nothing overrides.</li>
 * </ol>
 */
final class WebhookKafkaConfig {

    static final String BINDER = "spring.cloud.stream.kafka.binder";
    private static final String KAFKA_PROPERTIES = "org.springframework.boot.kafka.autoconfigure.KafkaProperties";

    private WebhookKafkaConfig() {
    }

    static Map<String, Object> producer(WebhookGatewayProperties properties, Environment environment, BeanFactory beans) {
        var config = new HashMap<String, Object>(springKafka(environment, beans,
                ClassUtils.isPresent(KAFKA_PROPERTIES, WebhookKafkaConfig.class.getClassLoader())));
        config.putAll(map(environment, BINDER + ".configuration"));
        config.putAll(map(environment, BINDER + ".producer-properties"));
        config.putAll(properties.getKafka().getProperties());
        // A transactional id (spring.kafka.producer.transaction-id-prefix) would make every send fail
        // outside a transaction: this producer is never transactional.
        config.remove(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(properties, environment));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return config;
    }

    /** The gateway's own brokers, else the binder's, else Spring Kafka's. */
    static String bootstrapServers(WebhookGatewayProperties properties, Environment environment) {
        var own = properties.getKafka().getBootstrapServers();
        if (own != null && !own.isBlank()) {
            return own;
        }
        return environment.getProperty(BINDER + ".brokers",
                environment.getProperty("spring.kafka.bootstrap-servers", "localhost:9092"));
    }

    static Map<String, Object> springKafka(Environment environment, BeanFactory beans, boolean springBootKafka) {
        if (springBootKafka) {
            return SpringKafka.producerProperties(environment, beans);
        }
        return new HashMap<>(map(environment, "spring.kafka.properties"));
    }

    private static Map<String, String> map(Environment environment, String prefix) {
        return Binder.get(environment).bind(prefix, Bindable.mapOf(String.class, String.class)).orElseGet(Map::of);
    }

    /** Touches {@link KafkaProperties} only when Spring Boot's Kafka support is on the classpath. */
    static final class SpringKafka {

        private SpringKafka() {
        }

        static Map<String, Object> producerProperties(Environment environment, BeanFactory beans) {
            var bean = beans == null ? null : beans.getBeanProvider(KafkaProperties.class).getIfAvailable();
            var kafka = bean != null ? bean : Binder.get(environment).bindOrCreate("spring.kafka", KafkaProperties.class);
            return kafka.buildProducerProperties();
        }
    }
}
