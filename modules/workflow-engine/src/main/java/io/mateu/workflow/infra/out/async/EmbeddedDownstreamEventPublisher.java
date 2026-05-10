package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.application.out.EmbeddedTaskExecutor;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "embedded")
@RequiredArgsConstructor
public class EmbeddedDownstreamEventPublisher implements DownstreamEventPublisher {

    private final EmbeddedTaskExecutor embeddedTaskExecutor;

    @Override
    public void publish(DomainEvent event) {
        embeddedTaskExecutor.execute((TaskExecutionRequested) event);
    }
}
