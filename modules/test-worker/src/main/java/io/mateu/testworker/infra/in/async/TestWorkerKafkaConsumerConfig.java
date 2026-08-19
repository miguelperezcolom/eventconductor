package io.mateu.testworker.infra.in.async;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.testworker.application.SimulatedTaskHandler;
import io.mateu.workflow.worker.CancelledTasks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * The binding: tasks and cancellations in on {@code downstream}, replies out on {@code upstream}.
 *
 * <p><b>Switchable off with {@code worker.kafka.enabled=false}</b>, for an application that wants
 * the test worker's pages and stores without a broker — browsing what was recorded from inside an
 * embedded app, which is a real thing to want and is what the browser tests do. It has to be a
 * property rather than {@code @ConditionalOnBean(StreamOperations.class)}: this class is found by
 * component scan, and a scanned class is evaluated before the auto-configuration that would supply
 * that bean, so the condition would read false even where Cloud Stream is present.
 *
 * <p>Opt-out, not opt-in, because the failure it guards against is silence: a worker that starts,
 * looks healthy and consumes nothing. The default wires the binding, and
 * {@code AppApplicationTests} in the standalone app asserts the bean is really there — if this ever
 * stops wiring in the application that ships it, that test fails rather than a queue quietly
 * filling up.
 *
 * <p>Deliberately thin. It is the same routing the sample worker does — cancellations are
 * remembered, tasks are executed, everything else is ignored — and everything that makes this
 * worker a test instrument lives behind {@link SimulatedTaskHandler}, where it can be tested
 * without a broker.
 *
 * <p>{@link StreamOperations} rather than {@code StreamBridge} for the reason the sample worker
 * gives: the bridge is final, and behaviour that cannot be tested is not behaviour anyone should
 * trust.
 */
@Configuration
@ConditionalOnProperty(name = "worker.kafka.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class TestWorkerKafkaConsumerConfig {

    private final SimulatedTaskHandler handler;

    @Bean
    public CancelledTasks cancelledTasks() {
        return new CancelledTasks();
    }

    @Bean
    public Function<Flux<DomainEvent>, Mono<Void>> consumeWorkerEvent(CancelledTasks cancelledTasks,
                                                                      StreamOperations streamBridge) {
        return events -> events
                .flatMap(event -> route(streamBridge, event, cancelledTasks))
                .then();
    }

    private Mono<Void> route(StreamOperations bridge, DomainEvent event, CancelledTasks cancelled) {
        if (event instanceof TaskCancellationRequested cancellation) {
            cancelled.accept(cancellation);
            return Mono.empty();
        }
        if (event instanceof TaskExecutionRequested task) {
            // A throw from here fails the flux, so the offset is not committed and Kafka
            // redelivers the task. That is intended: a reply the broker refused is worse than a
            // task done twice, and every scenario this worker plays is idempotent by construction.
            return handler.handle(bridge, task, cancelled);
        }
        return Mono.empty();
    }
}
