package io.mateu.workflow.infra.in.async.processdomainevent.domaineventhandlers;

import io.mateu.workflow.application.out.MessageSubscriptionRepository;
import io.mateu.workflow.application.readmodel.MessageSubscription;
import io.mateu.workflow.application.services.messagerouting.WaitingMessageFilter;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.domain.MessageSubscriptionChanged;
import java.time.LocalDateTime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Projects a {@link MessageSubscriptionChanged} into the two pieces of receiving-side routing state:
 *
 * <ul>
 *   <li>the shared message-subscription table (layer 2) — a step that started waiting is written with
 *       this shard's id so a sharded router can send a matching message straight here, one that
 *       stopped waiting is removed. The table is the shared routing database, present only in a
 *       sharded deployment; when it is absent (a single-database engine) this half is skipped. Uses an
 *       {@link ObjectProvider} rather than a bean condition so it is robust to configuration ordering;
 *   <li>the per-shard {@link WaitingMessageFilter} (layer 3) — a start adds the pair so a broadcast the
 *       shard cannot match is dropped without a query. This half needs no shared database, so it runs
 *       whether or not the subscription table is configured; the filter no-ops when disabled.
 * </ul>
 */
@Service
public class MessageSubscriptionProjectionHandler implements DomainEventHandler<MessageSubscriptionChanged> {

    private final ObjectProvider<MessageSubscriptionRepository> subscriptionRepository;
    private final WaitingMessageFilter waitingMessageFilter;
    private final String shardId;

    public MessageSubscriptionProjectionHandler(
            ObjectProvider<MessageSubscriptionRepository> subscriptionRepository,
            WaitingMessageFilter waitingMessageFilter,
            @Value("${workflow.sharding.shard-id:}") String shardId) {
        this.subscriptionRepository = subscriptionRepository;
        this.waitingMessageFilter = waitingMessageFilter;
        this.shardId = (shardId == null || shardId.isBlank()) ? null : shardId;
    }

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return MessageSubscriptionChanged.class;
    }

    @Override
    public void handle(MessageSubscriptionChanged event) {
        // Layer 3 first: local, needs no shared store, and adding a pair can only ever cost a query,
        // never a match — so it is safe to do before the table write and regardless of it.
        if (event.waiting()) {
            waitingMessageFilter.add(event.messageName(), event.correlationKey());
        }

        var store = subscriptionRepository.getIfAvailable();
        if (store == null) {
            return; // no shared routing store: single-database engine, nothing more to project
        }
        if (event.waiting()) {
            store.subscribe(new MessageSubscription(event.stepExecutionId(), event.messageName(),
                    event.correlationKey(), shardId, LocalDateTime.now()));
        } else {
            store.unsubscribe(event.stepExecutionId());
        }
    }
}
