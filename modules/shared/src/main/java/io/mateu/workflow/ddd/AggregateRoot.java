package io.mateu.workflow.ddd;

import java.util.ArrayList;
import java.util.List;

public abstract class AggregateRoot {

    private final List<DomainEvent> events = new ArrayList<>();

    public synchronized void send(DomainEvent event) {
        events.add(event);
    }

    public synchronized List<DomainEvent> popEvents() {
        var accumulated = new ArrayList<DomainEvent>(events);
        events.clear();
        return accumulated;
    }

}
