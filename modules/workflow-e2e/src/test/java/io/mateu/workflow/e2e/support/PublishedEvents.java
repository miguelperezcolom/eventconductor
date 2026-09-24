package io.mateu.workflow.e2e.support;

import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;
import io.mateu.workflow.infra.out.events.ExternalEventPublished;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records what PUBLISH_EVENT steps published in embedded mode (the default ApplicationEvent publisher). */
public class PublishedEvents {

    private final List<ExternalEventRequested> events = new CopyOnWriteArrayList<>();

    @EventListener
    public void on(ExternalEventPublished published) {
        events.add(published.event());
    }

    public List<ExternalEventRequested> forProcess(String processId) {
        return events.stream().filter(event -> processId.equals(event.processId())).toList();
    }
}
