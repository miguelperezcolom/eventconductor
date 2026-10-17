package io.mateu.workflow.events.kafka;

import io.mateu.workflow.application.services.EventTriggerService;
import io.mateu.workflow.application.services.InboundEventService;
import io.mateu.workflow.infra.config.EventDestinationsProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Domain events in over Kafka: in kafka mode, consumes the topics of the destinations that event
 * triggers and {@code onEvent} waits name and hands each record to the engine's
 * {@link InboundEventService}. {@code workflow.events.consumer.enabled=false} keeps it from listening.
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.kafka.listener.ConcurrentMessageListenerContainer")
@ConditionalOnProperty(name = "workflow.mode", havingValue = "kafka")
public class InboundEventKafkaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({InboundEventService.class, EventTriggerService.class})
    public InboundEventKafkaConsumer inboundEventKafkaConsumer(InboundEventService inboundEventService,
                                                               EventTriggerService eventTriggerService,
                                                               EventDestinationsProperties properties,
                                                               Environment environment) {
        return new InboundEventKafkaConsumer(inboundEventService, eventTriggerService, properties, environment);
    }
}
