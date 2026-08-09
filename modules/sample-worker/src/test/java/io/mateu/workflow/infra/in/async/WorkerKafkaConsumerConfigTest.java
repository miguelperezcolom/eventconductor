package io.mateu.workflow.infra.in.async;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.workflow.worker.CancelledTasks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worker's side of the cancellation contract. The engine has always published
 * {@link TaskCancellationRequested}; this worker used to filter it out, keep working, and report
 * the task done on a process that was already over.
 */
class WorkerKafkaConsumerConfigTest {

    private final List<TaskStatusChanged> sent = new CopyOnWriteArrayList<>();
    private CancelledTasks cancelledTasks;
    private Function<Flux<DomainEvent>, Mono<Void>> consumer;

    /** Records what the worker reports back, and accepts it the way a healthy broker would. */
    private final StreamOperations broker = new StreamOperations() {
        @Override
        public boolean send(String bindingName, Object data) {
            if (data instanceof TaskStatusChanged reply) {
                sent.add(reply);
            }
            return true;
        }

        @Override
        public boolean send(String bindingName, Object data, org.springframework.util.MimeType outputContentType) {
            return send(bindingName, data);
        }

        @Override
        public boolean send(String bindingName, String binderName, Object data) {
            return send(bindingName, data);
        }

        @Override
        public boolean send(String bindingName, String binderName, Object data,
                            org.springframework.util.MimeType outputContentType) {
            return send(bindingName, data);
        }
    };

    @BeforeEach
    void setUp() {
        var config = new WorkerKafkaConsumerConfig(broker, Duration.ofMillis(50));
        cancelledTasks = config.cancelledTasks();
        consumer = config.consumeWorkerEvent(cancelledTasks);
    }

    private TaskExecutionRequested task(String id) {
        return new TaskExecutionRequested(id, "p-1", "wd-1", "step-1", "task-1", List.of());
    }

    private List<TaskStatus> statuses() {
        return sent.stream().map(TaskStatusChanged::status).toList();
    }

    @Test
    void aTaskNobodyCancelsIsRunAndReportedDone() {
        consumer.apply(Flux.just(task("se-1"))).block(Duration.ofSeconds(5));

        assertThat(statuses()).containsExactly(TaskStatus.RUNNING, TaskStatus.COMPLETED);
    }

    @Test
    void aTaskCancelledWhileItRunsIsAbandonedWithoutBeingReportedDone() {
        // The cancellation lands during the work: the work is dropped and no COMPLETED goes back,
        // which is the whole point — the engine cancelled the step because nothing it produces
        // can be used any more.
        var events = Flux.<DomainEvent>concat(
                Flux.just(task("se-1")),
                Mono.delay(Duration.ofMillis(10)).map(tick -> new TaskCancellationRequested("se-1")));

        consumer.apply(events).block(Duration.ofSeconds(5));

        assertThat(statuses()).containsExactly(TaskStatus.RUNNING);
    }

    @Test
    void aTaskWhoseCancellationOvertookItIsNeverStarted() {
        // Cancellation and task travel on different partitions, so this order is possible and
        // says nothing about which happened first. Not even RUNNING should go back.
        var events = Flux.<DomainEvent>just(new TaskCancellationRequested("se-1"), task("se-1"));

        consumer.apply(events).block(Duration.ofSeconds(5));

        assertThat(sent).isEmpty();
    }

    @Test
    void aCancellationForAnotherTaskLeavesThisOneAlone() {
        var events = Flux.<DomainEvent>just(new TaskCancellationRequested("se-other"), task("se-1"));

        consumer.apply(events).block(Duration.ofSeconds(5));

        assertThat(statuses()).containsExactly(TaskStatus.RUNNING, TaskStatus.COMPLETED);
    }
}
