package io.mateu.workflow.ddd;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AggregateRootTest {

    static class TestAggregate extends AggregateRoot {
    }

    static class TestEvent implements DomainEvent {
    }

    @Test
    void popEventsReturnsEmptyListWhenNoEventsSent() {
        var aggregate = new TestAggregate();
        assertThat(aggregate.popEvents()).isEmpty();
    }

    @Test
    void sendAndPopEventsReturnsAllSentEvents() {
        var aggregate = new TestAggregate();
        var event1 = new TestEvent();
        var event2 = new TestEvent();

        aggregate.send(event1);
        aggregate.send(event2);

        List<DomainEvent> events = aggregate.popEvents();
        assertThat(events).containsExactly(event1, event2);
    }

    @Test
    void popEventsClearsTheEventList() {
        var aggregate = new TestAggregate();
        aggregate.send(new TestEvent());

        aggregate.popEvents();

        assertThat(aggregate.popEvents()).isEmpty();
    }

    @Test
    void multiplePopsEachReturnOnlyNewEvents() {
        var aggregate = new TestAggregate();
        var event1 = new TestEvent();
        aggregate.send(event1);
        List<DomainEvent> first = aggregate.popEvents();

        var event2 = new TestEvent();
        aggregate.send(event2);
        List<DomainEvent> second = aggregate.popEvents();

        assertThat(first).containsExactly(event1);
        assertThat(second).containsExactly(event2);
    }
}
