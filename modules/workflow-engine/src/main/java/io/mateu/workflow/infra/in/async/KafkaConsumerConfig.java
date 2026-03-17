package io.mateu.workflow.infra.in.async;

import io.mateu.workflow.application.usecases.processdomainevent.ProcessDomainEventCommand;
import io.mateu.workflow.application.usecases.processdomainevent.ProcessDomainEventUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class KafkaConsumerConfig {

    final ProcessDomainEventUseCase processDomainEventUseCase;

    @Bean
    public Consumer<Flux<DomainEvent>> processDomainEvent() {
        return eventos -> eventos
                .map(ProcessDomainEventCommand::new)
                .doOnNext(processDomainEventUseCase::handle)
                .subscribe();
    }

}
