package io.mateu.workflow.infra.in.async.processdomainevent.domaineventhandlers;

import io.mateu.workflow.application.out.MessageSubscriptionRepository;
import io.mateu.workflow.application.readmodel.MessageSubscription;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.ddd.DomainEventHandler;
import io.mateu.workflow.dtos.events.domain.MessageSubscriptionChanged;
import java.time.LocalDateTime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Projects a {@link MessageSubscriptionChanged} into the shared message-subscription routing table:
 * a step that started waiting is written with this shard's id (so a sharded router can send a
 * matching message straight here — layer 2), one that stopped waiting is removed.
 *
 * <p>The store is the shared routing database, present only in a sharded deployment; when it is
 * absent (a single-database engine, where routing is irrelevant) this is a no-op. Uses an
 * {@link ObjectProvider} rather than a bean condition so it is robust to configuration ordering.
 */
@Service
public class MessageSubscriptionProjectionHandler implements DomainEventHandler<MessageSubscriptionChanged> {

    private final ObjectProvider<MessageSubscriptionRepository> subscriptionRepository;
    private final String shardId;

    public MessageSubscriptionProjectionHandler(
            ObjectProvider<MessageSubscriptionRepository> subscriptionRepository,
            @Value("${workflow.sharding.shard-id:}") String shardId) {
        this.subscriptionRepository = subscriptionRepository;
        this.shardId = (shardId == null || shardId.isBlank()) ? null : shardId;
    }

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return MessageSubscriptionChanged.class;
    }

    @Override
    public void handle(MessageSubscriptionChanged event) {
        var store = subscriptionRepository.getIfAvailable();
        if (store == null) {
            return; // no shared routing store: single-database engine, nothing to project
        }
        if (event.waiting()) {
            store.subscribe(new MessageSubscription(event.stepExecutionId(), event.messageName(),
                    event.correlationKey(), shardId, LocalDateTime.now()));
        } else {
            store.unsubscribe(event.stepExecutionId());
        }
    }
}
