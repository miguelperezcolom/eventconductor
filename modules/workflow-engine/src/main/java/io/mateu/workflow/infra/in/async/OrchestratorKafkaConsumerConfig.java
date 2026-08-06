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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Configuration
@ConditionalOnProperty(name = "workflow.mode", havingValue = "kafka")
@RequiredArgsConstructor
@Slf4j
public class OrchestratorKafkaConsumerConfig {

    final ProcessDomainEventUseCase processDomainEventUseCase;
    final ProcessUpstreamEventUseCase processUpstreamEventUseCase;
    final TransactionTemplate transactionTemplate;
    final io.mateu.workflow.application.out.DeadLetterPublisher deadLetterPublisher;

    @Bean
    public Consumer<Message<List<DomainEvent>>> consumeOutbox() {
        return message -> perProcess(message.getPayload(), event ->
                processDomainEventUseCase.handle(new ProcessDomainEventCommand(event)), "outbox");
    }

    @Bean
    public Consumer<List<DomainEvent>> consumeUpstream() {
        return events -> perProcess(events, event ->
                processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(event)), "upstream");
    }

    /**
     * The shared cross-shard messages channel. Every shard binds this to the one {@code messages}
     * topic under its own consumer group, so each receives every {@link io.mateu.workflow.dtos.events.integration.MessageReceived}
     * and correlates it against its own WAIT_FOR_MESSAGE steps; the shard that owns the waiter resumes
     * it, the rest match nothing and drop it (fail-closed). Correlation is the same upstream path — only
     * the topic the message arrived on differs. Bound only where {@code messages} is configured (sharded
     * deployments); an unlisted function bean is inert, so single-cluster deployments are unaffected.
     */
    @Bean
    public Consumer<List<DomainEvent>> consumeMessages() {
        return events -> perProcess(events, event ->
                processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(event)), "messages");
    }

    /**
     * Commits a poll batch as one transaction per process rather than one per event — a busy
     * batch carries several events for the same process, and collapsing those into a single
     * commit is where the saving is.
     *
     * <p>Per process, and not per batch, on purpose. A batch-wide transaction sounds better and
     * is a trap: one failure inside it — an optimistic conflict, which is exactly what a
     * rebalance produces — marks the whole transaction rollback-only, so every other event that
     * believed it had committed is rolled back with it and redelivered, and the pods least able
     * to cope get the largest batches to redo. Per process the blast radius is one process, which
     * is already the unit of redelivery.
     *
     * <p>Events of a process stay in the order they arrived: Kafka orders them within a
     * partition, and the grouping preserves encounter order. Events with no process of their own
     * each get their own transaction.
     */
    private void perProcess(List<DomainEvent> events, Consumer<DomainEvent> handle) {
        perProcess(events, handle, "unknown");
    }

    private void perProcess(List<DomainEvent> events, Consumer<DomainEvent> handle, String source) {
        var byProcess = new LinkedHashMap<String, List<DomainEvent>>();
        for (var event : events) {
            var key = event.partitionKey() == null
                    // No process: give it a group of its own so it neither drags others down nor
                    // is dragged down by them.
                    ? "\u0000unkeyed-" + System.identityHashCode(event)
                    : event.partitionKey();
            byProcess.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(event);
        }
        for (Map.Entry<String, List<DomainEvent>> group : byProcess.entrySet()) {
            try {
                transactionTemplate.executeWithoutResult(status ->
                        group.getValue().forEach(event -> {
                            log.debug("Processing {}", event);
                            handle.accept(event);
                        }));
            } catch (Exception e) {
                if (io.mateu.workflow.application.services.EventFailures.isRetryable(e)) {
                    // The environment, not the event: let it out so the binder redelivers the
                    // batch rather than committing over work that never happened. Handlers are
                    // idempotent, so repeating the slices that did commit is harmless.
                    throw e;
                }
                // This slice will fail the same way forever. Park its events where someone can
                // look at them and replay them, and let the rest of the batch through — the
                // alternative is a poison event stalling a partition for good.
                group.getValue().forEach(event -> deadLetterPublisher.park(event, e, source));
            }
        }
    }

}
