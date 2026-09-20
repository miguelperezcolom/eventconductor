package io.mateu.workflow.application.services.messagerouting;

import io.mateu.workflow.application.out.IngressPublisher;
import io.mateu.workflow.application.out.MessagePublisher;
import io.mateu.workflow.application.out.ProcessPlacementRepository;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The single place cross-shard message routing is decided (decision 2): both the external-message
 * path ({@code MessageDispatcher}) and the {@code SEND_MESSAGE} relay hand a {@link MessageReceived}
 * here instead of broadcasting it to the shared {@code messages} topic.
 *
 * <p>Routing only filters — the shard that receives a routed message runs the normal correlation, so
 * a wrong guess costs a query or a broadcast, never a lost message (decision 8). Layers, in order:
 * <ol>
 *   <li><b>Placement</b> — for a {@link MessageClassification#BUSINESS_KEY} name, the correlation key
 *       is a business key; the placement store says which shard owns it, and the message goes to that
 *       shard's {@code upstream} (decision 4).</li>
 *   <li><b>Subscriptions</b> — added in phase 2; a name/key the placement store does not resolve
 *       (an expression key, or a business key that was never placed, e.g. a child process) is looked
 *       up in the shared subscription table and sent to each subscribed shard.</li>
 *   <li><b>Broadcast</b> — the residual: publish to {@code messages}, exactly as today.</li>
 * </ol>
 * Enabled by {@code workflow.sharding.message-routing.enabled}; with it off the caller broadcasts as
 * before.
 */
@Component
public class MessageRouter {

    private final MessageClassifier classifier;
    private final ObjectProvider<ProcessPlacementRepository> placementRepository;
    private final ObjectProvider<io.mateu.workflow.application.out.MessageSubscriptionRepository> subscriptionRepository;
    private final IngressPublisher ingressPublisher;
    private final MessagePublisher messagePublisher;
    private final MessageRoutingMetrics metrics;
    private final boolean enabled;

    public MessageRouter(MessageClassifier classifier,
                         ObjectProvider<ProcessPlacementRepository> placementRepository,
                         ObjectProvider<io.mateu.workflow.application.out.MessageSubscriptionRepository> subscriptionRepository,
                         IngressPublisher ingressPublisher,
                         MessagePublisher messagePublisher,
                         MessageRoutingMetrics metrics,
                         @Value("${workflow.sharding.message-routing.enabled:false}") boolean enabled) {
        this.classifier = classifier;
        this.placementRepository = placementRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.ingressPublisher = ingressPublisher;
        this.messagePublisher = messagePublisher;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    /** Whether message routing is on; when false the caller keeps its current (broadcast) behaviour. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Route one message to the shard(s) that can correlate it, falling through to broadcast. */
    public void route(MessageReceived message) {
        var classification = classifier.classify(message.messageName());

        // Layer 1 — placement: a business-key message goes straight to the shard that owns the key.
        if (classification == MessageClassification.BUSINESS_KEY) {
            var shard = placedShard(message.correlationKey());
            if (shard != null) {
                ingressPublisher.publishToShard(message, shard);
                metrics.routed("placement");
                return;
            }
        }

        // Layer 2 — subscriptions: an expression key, or a business key the placement store does not
        // own (e.g. a child process), is resolved by the shared subscription table. A message a
        // waiter opted out of routing for (BROADCAST) skips straight to layer 3.
        if (classification != MessageClassification.BROADCAST) {
            var shards = subscribedShards(message.messageName(), message.correlationKey());
            if (!shards.isEmpty()) {
                shards.forEach(shard -> ingressPublisher.publishToShard(message, shard));
                metrics.routed("subscription");
                return;
            }
        }

        // Layer 3 — broadcast: the residual, exactly as before routing existed.
        messagePublisher.publish(message);
        metrics.routed("broadcast");
    }

    private java.util.List<String> subscribedShards(String messageName, String correlationKey) {
        var store = subscriptionRepository.getIfAvailable();
        if (store == null || correlationKey == null || correlationKey.isBlank()) {
            return java.util.List.of();
        }
        try {
            var shards = store.shardsWaitingFor(messageName, correlationKey);
            return shards == null ? java.util.List.of() : shards;
        } catch (RuntimeException e) {
            metrics.subscriptionLookupFailed();
            return java.util.List.of();
        }
    }

    private String placedShard(String correlationKey) {
        var store = placementRepository.getIfAvailable();
        if (store == null || correlationKey == null || correlationKey.isBlank()) {
            return null;
        }
        try {
            return store.find(correlationKey).orElse(null);
        } catch (RuntimeException e) {
            metrics.placementLookupFailed();
            return null;
        }
    }
}
