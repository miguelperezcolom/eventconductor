package io.mateu.testworker;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.testworker.application.ScenarioResolver;
import io.mateu.testworker.application.SimulatedTaskHandler;
import io.mateu.testworker.application.TaskSimulator;
import io.mateu.testworker.infra.in.async.TestWorkerKafkaConsumerConfig;
import io.mateu.testworker.infra.out.persistence.InMemoryReceivedTaskStore;
import io.mateu.testworker.infra.out.persistence.InMemoryTaskOverrideStore;
import io.mateu.workflow.worker.CancelledTasks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.function.Function;

import static io.mateu.testworker.Tasks.task;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The routing, which is the whole of the binding: cancellations are remembered, tasks are played,
 * and everything else on {@code downstream} is left alone.
 */
class TestWorkerKafkaConsumerConfigTest {

    private RecordingBroker broker;
    private CancelledTasks cancelled;
    private Function<Flux<DomainEvent>, Mono<Void>> consumer;

    @BeforeEach
    void setUp() {
        broker = new RecordingBroker();
        var handler = new SimulatedTaskHandler(
                new ScenarioResolver(new InMemoryTaskOverrideStore(), Duration.ZERO),
                new TaskSimulator(),
                new InMemoryReceivedTaskStore(100));
        var config = new TestWorkerKafkaConsumerConfig(handler);
        cancelled = config.cancelledTasks();
        consumer = config.consumeWorkerEvent(cancelled, broker);
    }

    @Test
    void a_task_is_played() {
        consumer.apply(Flux.just(task("charge-card"))).block();

        assertThat(broker.statusValues()).containsExactly(TaskStatus.RUNNING, TaskStatus.COMPLETED);
    }

    @Test
    void a_cancellation_that_arrives_first_stops_the_task_that_follows_it() {
        // Different partitions, so arriving second says nothing about which happened first. The
        // worker every other worker is copied from used to drop these on the floor.
        consumer.apply(Flux.just(
                new TaskCancellationRequested("exec-1"),
                task("charge-card"))).block();

        assertThat(broker.sent).isEmpty();
    }

    @Test
    void an_event_this_worker_has_no_business_with_is_ignored() {
        consumer.apply(Flux.just(new UnrelatedEvent())).block();

        assertThat(broker.sent).isEmpty();
    }

    private record UnrelatedEvent() implements DomainEvent {
    }
}
