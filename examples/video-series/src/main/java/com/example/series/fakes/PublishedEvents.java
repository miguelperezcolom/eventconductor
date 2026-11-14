package com.example.series.fakes;

import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;
import io.mateu.workflow.infra.out.events.ExternalEventPublished;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * LOCAL STAND-IN — a subscriber to the events the processes publish. Embedded, with no broker, a PUBLISH_EVENT
 * reaches the application's {@code ExternalEventPublisher}; the default one publishes a Spring
 * {@link ExternalEventPublished}, which this records. {@code GET /fake/events} shows them, newest last.
 * An application with a broker provides its own {@code ExternalEventPublisher} instead.
 */
@RestController
public class PublishedEvents {

    private final List<ExternalEventRequested> events = new CopyOnWriteArrayList<>();

    @EventListener
    void on(ExternalEventPublished published) {
        events.add(published.event());
    }

    @GetMapping("/fake/events")
    public List<ExternalEventRequested> list() {
        return List.copyOf(events);
    }
}
