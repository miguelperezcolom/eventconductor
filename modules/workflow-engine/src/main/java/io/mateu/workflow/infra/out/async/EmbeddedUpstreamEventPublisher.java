package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "embedded", matchIfMissing = true)
@RequiredArgsConstructor
public class EmbeddedUpstreamEventPublisher implements UpstreamEventPublisher {

    private final ProcessUpstreamEventUseCase processUpstreamEventUseCase;

    @Override
    public void publish(DomainEvent event) {
        processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(event));
    }
}
