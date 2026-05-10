package io.mateu.workflow.application.out;

import io.mateu.workflow.ddd.DomainEvent;

public interface DownstreamEventPublisher {
    void publish(DomainEvent event);
}
