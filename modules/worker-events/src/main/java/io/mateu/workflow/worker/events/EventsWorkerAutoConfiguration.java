package io.mateu.workflow.worker.events;

import io.mateu.workflow.worker.api.TaskRegistration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Registers the built-in {@code publish-event@1} task wherever this module is on the classpath: the
 * transport (worker-embedded in the engine's pod, worker-kafka in the orchestrator or a worker
 * deployment) collects it like any task. {@code workflow.events.worker.enabled=false} leaves it out.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "workflow.events.worker.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(EventsWorkerProperties.class)
public class EventsWorkerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PublishEventHandler publishEventHandler(ObjectProvider<EventSink> sink) {
        return new PublishEventHandler(sink.getIfAvailable());
    }

    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public TaskRegistration publishEventTaskRegistration(PublishEventHandler handler, EventsWorkerProperties properties) {
        return new TaskRegistration<>("publish-event", 1, properties.getWorker().getTopic(), PublishEventHandler.Input.class,
                (Class<Map<String, Object>>) (Class) Map.class, handler);
    }

    /** Kafka mode: to the destination's topic. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.cloud.stream.function.StreamBridge")
    @ConditionalOnProperty(name = "workflow.mode", havingValue = "kafka")
    static class Kafka {

        @Bean
        @ConditionalOnMissingBean(EventSink.class)
        EventSink kafkaEventSink(org.springframework.cloud.stream.function.StreamBridge streamBridge,
                                 EventsWorkerProperties properties) {
            return new KafkaEventSink(streamBridge, properties);
        }
    }

    /** Embedded mode, next to the engine: through its delivery. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.mateu.workflow.infra.out.events.PublishedEventDelivery")
    @ConditionalOnProperty(name = "workflow.mode", havingValue = "embedded", matchIfMissing = true)
    static class Embedded {

        @Bean
        @ConditionalOnMissingBean(EventSink.class)
        EventSink engineEventSink(ObjectProvider<io.mateu.workflow.infra.out.events.PublishedEventDelivery> delivery) {
            // Lazily: the engine's delivery reaches the engine's doors, and the embedded worker is built
            // with them too.
            return (event, trace) -> delivery.getObject().deliver(event);
        }
    }
}
