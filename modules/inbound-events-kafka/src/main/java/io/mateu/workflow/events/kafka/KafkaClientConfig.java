package io.mateu.workflow.events.kafka;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.core.env.Environment;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The client settings a Kafka client built by hand inherits from the application, so it connects to
 * the same cluster the same way the Spring Cloud Stream binder does — authentication and TLS included.
 * Lowest to highest precedence:
 * <ol>
 *   <li>Spring Kafka ({@code spring.kafka.*}: {@code security.*}, {@code ssl.*}, {@code properties.*} and
 *       the consumer-specific {@code consumer.*}), as {@link KafkaProperties#buildConsumerProperties()} builds them;</li>
 *   <li>the binder's {@code spring.cloud.stream.kafka.binder.configuration.*}, then its
 *       {@code consumer-properties.*};</li>
 *   <li>the client's own override map;</li>
 *   <li>the values the client requires to work — never overridable.</li>
 * </ol>
 */
final class KafkaClientConfig {

    static final String BINDER = "spring.cloud.stream.kafka.binder";

    private KafkaClientConfig() {
    }

    static Map<String, Object> consumer(Environment environment, Supplier<KafkaProperties> springKafka,
                                        Map<String, String> overrides, Map<String, Object> required) {
        var config = new HashMap<String, Object>(springKafka(environment, springKafka).buildConsumerProperties());
        config.putAll(map(environment, BINDER + ".configuration"));
        config.putAll(map(environment, BINDER + ".consumer-properties"));
        if (overrides != null) {
            config.putAll(overrides);
        }
        config.putAll(required);
        return config;
    }

    /** The application's Spring Kafka settings: its bean when there is one, else bound from the environment. */
    private static KafkaProperties springKafka(Environment environment, Supplier<KafkaProperties> springKafka) {
        var bean = springKafka == null ? null : springKafka.get();
        return bean != null ? bean : Binder.get(environment).bindOrCreate("spring.kafka", KafkaProperties.class);
    }

    private static Map<String, String> map(Environment environment, String prefix) {
        return Binder.get(environment).bind(prefix, Bindable.mapOf(String.class, String.class)).orElseGet(Map::of);
    }
}
