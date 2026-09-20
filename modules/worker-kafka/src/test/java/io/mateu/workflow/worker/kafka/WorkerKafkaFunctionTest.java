package io.mateu.workflow.worker.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.workflow.worker.CancelledTasks;
import io.mateu.workflow.worker.api.Cancellations;
import io.mateu.workflow.worker.api.TaskDispatcher;
import io.mateu.workflow.worker.api.TaskHandler;
import io.mateu.workflow.worker.api.TaskRegistration;
import io.mateu.workflow.worker.api.TaskRegistry;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class WorkerKafkaFunctionTest {

    record In(int qty) {}

    record Out(int total) {}

    private final RecordingStreamOperations bridge = new RecordingStreamOperations();
    private final CancelledTasks cancelledTasks = new CancelledTasks();

    private Function<Flux<DomainEvent>, reactor.core.publisher.Mono<Void>> function() {
        TaskHandler<In, Out> handler = (in, ctx) -> new Out(in.qty() * 10);
        var registry = new TaskRegistry(List.of(
                new TaskRegistration<>("place-order", 1, "t", In.class, Out.class, handler)));
        Cancellations cancellations = new CancelledTasksCancellations(cancelledTasks);
        var dispatcher = new TaskDispatcher(registry, new WorkerReplySink(bridge), cancellations,
                new ObjectMapper(), false);
        return new WorkerKafkaAutoConfiguration().consumeWorkerEvent(dispatcher, cancelledTasks);
    }

    @Test
    void it_runs_a_task_and_replies_completed() {
        var task = new TaskExecutionRequested("tx-1", "p-1", "wf", "step", "place-order@1",
                List.of(new Variable("qty", "3")));

        function().apply(Flux.just(task)).block();

        var completed = bridge.sent.stream()
                .map(RecordingStreamOperations.Sent::payload)
                .filter(TaskStatusChanged.class::isInstance)
                .map(TaskStatusChanged.class::cast)
                .filter(r -> r.status() == TaskStatus.COMPLETED)
                .findFirst()
                .orElseThrow();
        assertThat(completed.variables()).containsExactly(new Variable("total", "30"));
    }

    @Test
    void it_records_a_cancellation_and_does_not_treat_it_as_a_task() {
        var cancellation = new TaskCancellationRequested("other-tx");

        function().apply(Flux.<DomainEvent>just(cancellation)).block();

        assertThat(cancelledTasks.isCancelled("other-tx")).isTrue();
        assertThat(bridge.sent).isEmpty();
    }
}
