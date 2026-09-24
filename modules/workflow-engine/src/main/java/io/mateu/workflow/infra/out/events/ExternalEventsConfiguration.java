package io.mateu.workflow.infra.out.events;

import io.mateu.workflow.application.out.ExternalEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExternalEventsConfiguration {

    /** Replaced by any application bean of the same type (a Kafka, RabbitMQ or SNS publisher). */
    @Bean
    @ConditionalOnMissingBean(ExternalEventPublisher.class)
    public ExternalEventPublisher applicationEventExternalPublisher(ApplicationEventPublisher publisher) {
        return new ApplicationEventExternalPublisher(publisher);
    }
}
