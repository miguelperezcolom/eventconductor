package io.mateu.workflow.application.out;

import io.mateu.workflow.ddd.DomainEvent;

public interface UpstreamEventPublisher {
    void publish(DomainEvent event);
}
