package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.MessagePublisher;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The message routing switch. Off (single cluster) → the process's own {@code upstream}, exactly as
 * before. On (sharded) → the shared {@code messages} topic, so it can reach a waiter on any shard.
 * Exactly one of the two publishers is ever touched.
 */
@ExtendWith(MockitoExtension.class)
class MessageDispatcherTest {

    @Mock UpstreamEventPublisher upstreamEventPublisher;
    @Mock MessagePublisher messagePublisher;

    private final MessageReceived message = new MessageReceived("payment-received", "bk-1", List.of());

    @Test
    void routesToUpstreamWhenSharedTopicOff() {
        new MessageDispatcher(upstreamEventPublisher, messagePublisher, false).dispatch(message);

        verify(upstreamEventPublisher).publish(message);
        verifyNoInteractions(messagePublisher);
    }

    @Test
    void routesToSharedMessagesTopicWhenOn() {
        new MessageDispatcher(upstreamEventPublisher, messagePublisher, true).dispatch(message);

        verify(messagePublisher).publish(message);
        verifyNoInteractions(upstreamEventPublisher);
    }
}
