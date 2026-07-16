package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.application.out.EmbeddedTaskExecutor;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "embedded")
@RequiredArgsConstructor
@Slf4j
public class EmbeddedDownstreamEventPublisher implements DownstreamEventPublisher {

    private final EmbeddedTaskExecutor embeddedTaskExecutor;

    @Override
    public void publish(DomainEvent event) {
        // Cancellations (and any future downstream event type) also flow through here;
        // casting blindly would throw ClassCastException on TaskCancellationRequested.
        // Embedded executors run inline, so there is no remote in-flight work to cancel.
        if (event instanceof TaskExecutionRequested request) {
            embeddedTaskExecutor.execute(request);
        } else {
            log.debug("Ignoring downstream event {} in embedded mode", event.getClass().getSimpleName());
        }
    }
}
