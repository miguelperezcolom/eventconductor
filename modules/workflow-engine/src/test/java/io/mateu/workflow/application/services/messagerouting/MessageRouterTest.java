package io.mateu.workflow.application.services.messagerouting;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.mateu.workflow.application.out.IngressPublisher;
import io.mateu.workflow.application.out.MessagePublisher;
import io.mateu.workflow.application.out.MessageSubscriptionRepository;
import io.mateu.workflow.application.out.ProcessPlacementRepository;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class MessageRouterTest {

    private final MessageClassifier classifier = mock(MessageClassifier.class);
    private final ProcessPlacementRepository placement = mock(ProcessPlacementRepository.class);
    private final MessageSubscriptionRepository subscriptions = mock(MessageSubscriptionRepository.class);
    private final IngressPublisher ingressPublisher = mock(IngressPublisher.class);
    private final MessagePublisher messagePublisher = mock(MessagePublisher.class);
    private final MessageRoutingMetrics metrics = mock(MessageRoutingMetrics.class);

    private final MessageReceived message = new MessageReceived("orderPaid", "bk-1", List.of());

    @SuppressWarnings("unchecked")
    private MessageRouter router(ProcessPlacementRepository placementStore,
                                 MessageSubscriptionRepository subscriptionStore) {
        ObjectProvider<ProcessPlacementRepository> placementProvider = mock(ObjectProvider.class);
        when(placementProvider.getIfAvailable()).thenReturn(placementStore);
        ObjectProvider<MessageSubscriptionRepository> subscriptionProvider = mock(ObjectProvider.class);
        when(subscriptionProvider.getIfAvailable()).thenReturn(subscriptionStore);
        return new MessageRouter(classifier, placementProvider, subscriptionProvider,
                ingressPublisher, messagePublisher, metrics, true);
    }

    @Test
    void layer1_a_business_key_message_goes_to_the_shard_that_owns_the_key() {
        when(classifier.classify("orderPaid")).thenReturn(MessageClassification.BUSINESS_KEY);
        when(placement.find("bk-1")).thenReturn(Optional.of("shard-3"));

        router(placement, subscriptions).route(message);

        verify(ingressPublisher).publishToShard(message, "shard-3");
        verifyNoInteractions(messagePublisher);
    }

    @Test
    void layer2_an_expression_message_goes_to_every_subscribed_shard() {
        when(classifier.classify("orderPaid")).thenReturn(MessageClassification.EXPRESSION);
        when(subscriptions.shardsWaitingFor("orderPaid", "bk-1")).thenReturn(List.of("shard-a", "shard-b"));

        router(placement, subscriptions).route(message);

        verify(ingressPublisher).publishToShard(message, "shard-a");
        verify(ingressPublisher).publishToShard(message, "shard-b");
        verifyNoInteractions(messagePublisher);
    }

    @Test
    void layer2_catches_a_business_key_the_placement_store_does_not_own() {
        when(classifier.classify("orderPaid")).thenReturn(MessageClassification.BUSINESS_KEY);
        when(placement.find("bk-1")).thenReturn(Optional.empty()); // e.g. a child process, unplaced
        when(subscriptions.shardsWaitingFor("orderPaid", "bk-1")).thenReturn(List.of("shard-c"));

        router(placement, subscriptions).route(message);

        verify(ingressPublisher).publishToShard(message, "shard-c");
        verifyNoInteractions(messagePublisher);
    }

    @Test
    void layer3_broadcasts_when_no_layer_matches() {
        when(classifier.classify("orderPaid")).thenReturn(MessageClassification.EXPRESSION);
        when(subscriptions.shardsWaitingFor("orderPaid", "bk-1")).thenReturn(List.of());

        router(placement, subscriptions).route(message);

        verify(messagePublisher).publish(message);
        verify(ingressPublisher, never()).publishToShard(any(), any());
    }

    @Test
    void a_broadcast_message_skips_the_subscription_layer() {
        when(classifier.classify("orderPaid")).thenReturn(MessageClassification.BROADCAST);

        router(placement, subscriptions).route(message);

        verify(messagePublisher).publish(message);
        verifyNoInteractions(ingressPublisher, subscriptions);
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
