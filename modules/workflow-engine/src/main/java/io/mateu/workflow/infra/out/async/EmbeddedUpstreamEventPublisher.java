package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.out.DeadLetterPublisher;
import io.mateu.workflow.application.out.PoisonEventException;
import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "embedded", matchIfMissing = true)
@RequiredArgsConstructor
public class EmbeddedUpstreamEventPublisher implements UpstreamEventPublisher {

    private final ProcessUpstreamEventUseCase processUpstreamEventUseCase;
    // Optional: embedded mode ships no dead-letter destination today (a poison event surfaces to
    // the synchronous caller instead). Injected as a provider so that if one is ever added, poison
    // events are parked here exactly as the Kafka consumer parks them — same classification, both
    // modes.
    private final ObjectProvider<DeadLetterPublisher> deadLetterPublisher;

    @Override
    public void publish(DomainEvent event) {
        try {
            processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(event));
        } catch (PoisonEventException poison) {
            // The event is defective and no retry can fix it. Mirror the Kafka path: park it where
            // it can be looked at if a dead-letter destination exists; otherwise let it surface to
            // the synchronous caller (REST/MCP) as a clear, typed error rather than being retried
            // or lost.
            var deadLetters = deadLetterPublisher.getIfAvailable();
            if (deadLetters != null) {
                deadLetters.park(event, poison, "embedded-upstream");
                return;
            }
            throw poison;
        }
    }
}
