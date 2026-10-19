package io.mateu.workflow.infra.in.async.processdomainevent.domaineventhandlers;

import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.dtos.humantasks.HumanTaskEscalationRequested;
import io.mateu.workflow.ddd.DomainEventHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * A human task's escalation, relayed off the outbox to the worker side of the USER_TASK — the forms
 * engine — on the topic the task itself was dispatched to, the way a cancellation is. Written to the
 * outbox in the unit of work that recorded the escalation, so it goes out if and only if the
 * escalation fired. Embedded mode has no forms engine to tell: the publisher drops it there.
 */
@Service
@RequiredArgsConstructor
public class HumanTaskEscalationRequestedHandler implements DomainEventHandler<HumanTaskEscalationRequested> {

    final StepExecutionRepository stepExecutionRepository;
    final DownstreamEventPublisher downstreamEventPublisher;

    @Override
    public Class<? extends DomainEvent> eventClass() {
        return HumanTaskEscalationRequested.class;
    }

    @Override
    public void handle(HumanTaskEscalationRequested event) {
        var topic = stepExecutionRepository.findById(event.taskExecutionId())
                .map(StepExecution::topic)
                .orElse(null);
        downstreamEventPublisher.publish(event, topic);
    }
}
