package io.mateu.workflow.ddd;


public interface DomainEventHandler<DomainEventType extends DomainEvent> {

    default boolean canHandle(DomainEventType e) {
        return e.getClass().equals(eventClass());   
    }

    Class<? extends DomainEvent> eventClass();

    void handle(DomainEventType e);

}
