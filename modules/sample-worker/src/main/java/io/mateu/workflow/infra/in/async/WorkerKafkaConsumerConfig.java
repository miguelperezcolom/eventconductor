package io.mateu.workflow.infra.in.async;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.function.Function;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class WorkerKafkaConsumerConfig {

    final StreamBridge streamBridge;

    @Bean
    public Function<Flux<DomainEvent>, Mono<Void>> consumeWorkerEvent() { // Cambiado de Consumer a Function
        return eventos -> eventos
                .filter(event -> event instanceof TaskExecutionRequested)
                .cast(TaskExecutionRequested.class)
                .flatMap(event -> {
                    // 1. Primer envío
                    TaskStatusChanged inProgress = new TaskStatusChanged(event.taskExecutionId(), TaskStatus.RUNNING);
                    streamBridge.send("upstream", inProgress);

                    // 2. Delay y segundo envío
                    return Mono.just(event)
                            .delayElement(Duration.ofSeconds(5))
                            .map(e -> new TaskStatusChanged(e.taskExecutionId(), TaskStatus.COMPLETED))
                            .doOnNext(completedEvent -> streamBridge.send("upstream", completedEvent))
                            .then(); // Convertimos a Mono<Void> para este evento
                })
                .then(); // Retornamos un Mono<Void> que representa la finalización del flujo total
    }

}
