package io.mateu.workflow.infra.in.async;

import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventCommand;
import io.mateu.workflow.infra.in.async.processdomainevent.ProcessDomainEventUseCase;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

@Configuration
@ConditionalOnProperty(name = "workflow.mode", havingValue = "kafka")
@RequiredArgsConstructor
@Slf4j
public class OrchestratorKafkaConsumerConfig {

    final ProcessDomainEventUseCase processDomainEventUseCase;
    final ProcessUpstreamEventUseCase processUpstreamEventUseCase;

    @Bean
    public Consumer<Message<DomainEvent>> consumeOutbox() {
        return message -> {
                    DomainEvent event = message.getPayload(); // Aquí Spring ya debió convertirlo
                    log.info("Procesando: {}", event);
                    processDomainEventUseCase.handle(new ProcessDomainEventCommand(event));
                };
    }

    @Bean
    public Consumer<DomainEvent> consumeUpstream() {
        return event -> {
            log.info("Procesando: {}", event);
            processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(event));
        };
    }

}
