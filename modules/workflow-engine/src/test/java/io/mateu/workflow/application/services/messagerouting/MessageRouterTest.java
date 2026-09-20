package io.mateu.workflow.application.services.messagerouting;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.mateu.workflow.application.out.IngressPublisher;
import io.mateu.workflow.application.out.MessagePublisher;
import io.mateu.workflow.application.out.ProcessPlacementRepository;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class MessageRouterTest {

    private final MessageClassifier classifier = mock(MessageClassifier.class);
    private final ProcessPlacementRepository placement = mock(ProcessPlacementRepository.class);
    private final IngressPublisher ingressPublisher = mock(IngressPublisher.class);
    private final MessagePublisher messagePublisher = mock(MessagePublisher.class);
    private final MessageRoutingMetrics metrics = mock(MessageRoutingMetrics.class);

    private final MessageReceived message = new MessageReceived("orderPaid", "bk-1", List.of());

    @SuppressWarnings("unchecked")
    private MessageRouter router(ProcessPlacementRepository store) {
        ObjectProvider<ProcessPlacementRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        return new MessageRouter(classifier, provider, ingressPublisher, messagePublisher, metrics, true);
    }

    @Test
    void a_business_key_message_goes_to_the_shard_that_owns_the_key() {
        when(classifier.classify("orderPaid")).thenReturn(MessageClassification.BUSINESS_KEY);
        when(placement.find("bk-1")).thenReturn(Optional.of("shard-3"));

        router(placement).route(message);

        verify(ingressPublisher).publishToShard(message, "shard-3");
        verifyNoInteractions(messagePublisher);
    }

    @Test
    void a_business_key_that_is_not_placed_is_broadcast() {
        when(classifier.classify("orderPaid")).thenReturn(MessageClassification.BUSINESS_KEY);
        when(placement.find("bk-1")).thenReturn(Optional.empty());

        router(placement).route(message);

        verify(messagePublisher).publish(message);
        verify(ingressPublisher, never()).publishToShard(any(), any());
    }

    @Test
    void with_no_placement_store_a_business_key_message_is_broadcast() {
        when(classifier.classify("orderPaid")).thenReturn(MessageClassification.BUSINESS_KEY);

        router(null).route(message);

        verify(messagePublisher).publish(message);
        verifyNoInteractions(ingressPublisher);
    }

    @Test
    void an_expression_message_is_broadcast_in_phase_1() {
        when(classifier.classify("orderPaid")).thenReturn(MessageClassification.EXPRESSION);

        router(placement).route(message);

        verify(messagePublisher).publish(message);
        verifyNoInteractions(ingressPublisher);
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
