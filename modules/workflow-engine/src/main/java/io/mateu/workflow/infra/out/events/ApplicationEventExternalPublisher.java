package io.mateu.workflow.infra.out.events;

import io.mateu.workflow.application.out.ExternalEventPublisher;
import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;
import org.springframework.context.ApplicationEventPublisher;

/** The embedded default: the event, as a Spring application event, for in-process listeners. */
public class ApplicationEventExternalPublisher implements ExternalEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public ApplicationEventExternalPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void publish(ExternalEventRequested event) {
        applicationEventPublisher.publishEvent(new ExternalEventPublished(event));
    }
}
