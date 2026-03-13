package io.mateu.workflow.domain.shared;

public interface DomainEventHandler<DomainEventType extends DomainEvent> {

    default boolean canHandle(DomainEventType e) {
        return e.getClass().equals(eventClass());   
    }

    Class<? extends DomainEvent> eventClass();

    void handle(DomainEventType e);

}
