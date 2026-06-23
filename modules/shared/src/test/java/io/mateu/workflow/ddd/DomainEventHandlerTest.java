package io.mateu.workflow.ddd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DomainEventHandlerTest {

    static class SomeEvent implements DomainEvent {}
    static class OtherEvent implements DomainEvent {}

    static class SomeEventHandler implements DomainEventHandler<SomeEvent> {
        @Override
        public Class<? extends DomainEvent> eventClass() { return SomeEvent.class; }

        @Override
        public void handle(SomeEvent e) {}
    }

    @Test
    void canHandleReturnsTrueForMatchingEventType() {
        var handler = new SomeEventHandler();
        assertThat(handler.canHandle(new SomeEvent())).isTrue();
    }

    @Test
    void canHandleReturnsFalseForDifferentEventType() {
        var handler = new SomeEventHandler();
        // canHandle signature takes DomainEventType but we cast to test default logic
        DomainEventHandler<DomainEvent> raw = (DomainEventHandler<DomainEvent>) (DomainEventHandler<?>) handler;
        assertThat(raw.canHandle(new OtherEvent())).isFalse();
    }
}
