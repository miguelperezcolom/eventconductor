package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.CommandPublisher;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.dtos.events.integration.RetryProcessRequested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The command routing switch. Off (single cluster) → this shard's own {@code upstream}, where Kafka
 * partitioning already reaches the owning pod. On (sharded) → the {@link CommandPublisher}, which sends
 * it to the shard that owns the process. Exactly one publisher is touched.
 */
@ExtendWith(MockitoExtension.class)
class CommandDispatcherTest {

    @Mock UpstreamEventPublisher upstreamEventPublisher;
    @Mock CommandPublisher commandPublisher;

    private final RetryProcessRequested command = new RetryProcessRequested("p-1");

    @Test
    void routesToUpstreamWhenShardingOff() {
        new CommandDispatcher(upstreamEventPublisher, commandPublisher, false).dispatch(command);

        verify(upstreamEventPublisher).publish(command);
        verifyNoInteractions(commandPublisher);
    }

    @Test
    void routesToCommandPublisherWhenShardingOn() {
        new CommandDispatcher(upstreamEventPublisher, commandPublisher, true).dispatch(command);

        verify(commandPublisher).publish(command);
        verifyNoInteractions(upstreamEventPublisher);
    }
}
