package io.mateu.workflow.test;

import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;
import io.mateu.workflow.infra.out.events.ExternalEventPublished;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records what PUBLISH_EVENT steps published (embedded mode publishes them as application events). */
final class PublishedEventRecorder {

    private final List<ExternalEventRequested> events = new CopyOnWriteArrayList<>();

    @EventListener
    public void on(ExternalEventPublished published) {
        events.add(published.event());
    }

    List<ExternalEventRequested> forProcess(String processId) {
        return events.stream().filter(event -> processId.equals(event.processId())).toList();
    }

    void clear() {
        events.clear();
    }
}
