package io.mateu.workflow.infra.in.async;

import io.mateu.workflow.application.usecases.processdomainevent.ProcessDomainEventCommand;
import io.mateu.workflow.application.usecases.processdomainevent.ProcessDomainEventUseCase;
import io.mateu.workflow.application.usecases.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.application.usecases.processupstreamevent.ProcessUpstreamEventUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.function.Consumer;
import java.util.function.Function;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class OrchestratorKafkaConsumerConfig {

    final ProcessDomainEventUseCase processDomainEventUseCase;
    final ProcessUpstreamEventUseCase processUpstreamEventUseCase;

    @Bean
    public Function<Flux<Message<DomainEvent>>, Mono<Void>> consumeOutbox() {
        return flux -> flux
                .concatMap(message -> {
                    DomainEvent event = message.getPayload(); // Aquí Spring ya debió convertirlo
                    return Mono.fromRunnable(() -> {
                                log.info("Procesando: {}", event);
                                processDomainEventUseCase.handle(new ProcessDomainEventCommand(event));
                            })
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .then();
    }

    @Bean
    public Function<Flux<Message<DomainEvent>>, Mono<Void>> consumeUpstream() {
        return flux -> flux
                .concatMap(message -> {
                    DomainEvent event = message.getPayload(); // Aquí Spring ya debió convertirlo
                    return Mono.fromRunnable(() -> {
                                log.info("Procesando: {}", event);
                                processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(event));
                            })
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .then();
    }

}
