package io.mateu.workflow.infra.in.async;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.worker.WorkerReply;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.function.Function;

/**
 * The worker other workers get copied from, which is why it goes through {@link WorkerReply}
 * rather than calling {@code streamBridge.send} directly.
 *
 * <p>The direct call returns {@code false} when the broker will not take the message, and this
 * class used to discard that. The listener then completes normally, the offset is committed, and
 * a task the worker actually did is never reported — leaving the engine's step in {@code PENDING}
 * with nothing to time it out. Measured across a ninety-second broker outage: 3 352 replies lost
 * and 3 356 processes stuck permanently, with no error logged anywhere.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class WorkerKafkaConsumerConfig {

    final StreamBridge streamBridge;

    @Bean
    public Function<Flux<DomainEvent>, Mono<Void>> consumeWorkerEvent() {
        return events -> events
                .filter(event -> event instanceof TaskExecutionRequested)
                .cast(TaskExecutionRequested.class)
                .flatMap(event -> {
                    WorkerReply.running(streamBridge, event);
                    return Mono.just(event)
                            .delayElement(Duration.ofSeconds(2))
                            // A throw here fails the flux, so the offset is not committed and
                            // Kafka redelivers the task. That is the intended outcome, and it is
                            // why a worker handler has to be idempotent.
                            .doOnNext(done -> WorkerReply.completed(streamBridge, done, done.variables()))
                            .then();
                })
                .then();
    }

}
