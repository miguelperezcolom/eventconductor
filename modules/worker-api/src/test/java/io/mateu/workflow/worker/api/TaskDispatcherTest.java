package io.mateu.workflow.worker.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TaskDispatcherTest {

    record OrderInput(String ref, int qty, boolean express) {}

    record OrderOutput(String status, int total) {}

    static TaskExecutionRequested request(String taskId, List<Variable> variables) {
        return new TaskExecutionRequested("tx-1", "p-1", "wf-1", "step-a", taskId, variables);
    }

    static TaskRegistration<OrderInput, OrderOutput> registration(TaskHandler<OrderInput, OrderOutput> handler) {
        return new TaskRegistration<>("place-order", 1, "place-order.v1", OrderInput.class, OrderOutput.class, handler);
    }

    /** Records what the dispatcher reported so a test can assert the outcome. */
    static final class RecordingSink implements TaskReplySink {
        final List<TaskExecutionRequested> running = new ArrayList<>();
        final List<List<Variable>> completed = new ArrayList<>();
        final List<String> failed = new ArrayList<>();

        public void running(TaskExecutionRequested task) {
            running.add(task);
        }

        public void completed(TaskExecutionRequested task, List<Variable> variables) {
            completed.add(variables);
        }

        public void failed(TaskExecutionRequested task, List<Variable> variables, String reason) {
            failed.add(reason);
        }
    }

    static final class ProgrammableCancellations implements Cancellations {
        final List<Boolean> claims = new ArrayList<>();
        boolean cancelled = false;
        private final AtomicInteger claimCall = new AtomicInteger();

        ProgrammableCancellations(Boolean... perClaim) {
            claims.addAll(List.of(perClaim));
        }

        public boolean claim(String taskExecutionId) {
            int i = claimCall.getAndIncrement();
            return i < claims.size() && claims.get(i);
        }

        public boolean isCancelled(String taskExecutionId) {
            return cancelled;
        }
    }

    private TaskDispatcher dispatcher(TaskRegistry registry, TaskReplySink sink, Cancellations cancellations,
                                      boolean strict) {
        return new TaskDispatcher(registry, sink, cancellations, new ObjectMapper(), strict);
    }

    @Test
    void completes_with_bound_input_and_output_variables() {
        var handler = (TaskHandler<OrderInput, OrderOutput>) (in, ctx) ->
                new OrderOutput(in.express() ? "express" : "standard", in.qty() * 10);
        var registry = new TaskRegistry(List.of(registration(handler)));
        var sink = new RecordingSink();

        dispatcher(registry, sink, Cancellations.NONE, false).dispatch(request("place-order@1", List.of(
                new Variable("ref", "A-100"),
                new Variable("qty", "3"),
                new Variable("express", "true"))));

        assertThat(sink.failed).isEmpty();
        assertThat(sink.completed).hasSize(1);
        var out = sink.completed.get(0).stream().collect(
                java.util.stream.Collectors.toMap(Variable::name, Variable::value));
        assertThat(out).containsEntry("status", "express").containsEntry("total", "30");
    }

    @Test
    void keeps_a_plain_string_a_string_and_reads_json_shapes() {
        record Shapes(String name, Map<String, Object> meta, List<Integer> tags) {}
        var seen = new Object() { Shapes value; };
        TaskHandler<Shapes, Void> handler = (in, ctx) -> {
            seen.value = in;
            return null;
        };
        var registry = new TaskRegistry(List.of(
                new TaskRegistration<>("shape", 1, "shape.v1", Shapes.class, Void.class, handler)));

        dispatcher(registry, new RecordingSink(), Cancellations.NONE, false).dispatch(request("shape@1", List.of(
                new Variable("name", "not-json at all"),
                new Variable("meta", "{\"a\":1}"),
                new Variable("tags", "[1,2,3]"))));

        assertThat(seen.value.name()).isEqualTo("not-json at all");
        assertThat(seen.value.meta()).containsEntry("a", 1);
        assertThat(seen.value.tags()).containsExactly(1, 2, 3);
    }

    @Test
    void business_failure_is_reported_with_its_code() {
        TaskHandler<OrderInput, OrderOutput> handler = (in, ctx) -> {
            throw new TaskFailure("OUT_OF_STOCK");
        };
        var registry = new TaskRegistry(List.of(registration(handler)));
        var sink = new RecordingSink();

        dispatcher(registry, sink, Cancellations.NONE, false).dispatch(request("place-order@1", List.of()));

        assertThat(sink.completed).isEmpty();
        assertThat(sink.failed).containsExactly("OUT_OF_STOCK");
    }

    @Test
    void unexpected_exception_is_reported_as_a_failure() {
        TaskHandler<OrderInput, OrderOutput> handler = (in, ctx) -> {
            throw new IllegalStateException("boom");
        };
        var registry = new TaskRegistry(List.of(registration(handler)));
        var sink = new RecordingSink();

        dispatcher(registry, sink, Cancellations.NONE, false).dispatch(request("place-order@1", List.of()));

        assertThat(sink.failed).hasSize(1);
        assertThat(sink.failed.get(0)).contains("boom");
    }

    @Test
    void an_input_that_cannot_be_bound_fails_the_task() {
        TaskHandler<OrderInput, OrderOutput> handler = (in, ctx) -> new OrderOutput("ok", 0);
        var registry = new TaskRegistry(List.of(registration(handler)));
        var sink = new RecordingSink();

        dispatcher(registry, sink, Cancellations.NONE, false).dispatch(request("place-order@1", List.of(
                new Variable("qty", "\"not a number\""))));

        assertThat(sink.completed).isEmpty();
        assertThat(sink.failed).hasSize(1);
        assertThat(sink.failed.get(0)).contains("OrderInput");
    }

    @Test
    void a_cancellation_before_start_stops_the_task_silently() {
        var invoked = new AtomicInteger();
        TaskHandler<OrderInput, OrderOutput> handler = (in, ctx) -> {
            invoked.incrementAndGet();
            return new OrderOutput("ok", 0);
        };
        var registry = new TaskRegistry(List.of(registration(handler)));
        var sink = new RecordingSink();

        dispatcher(registry, sink, new ProgrammableCancellations(true), false)
                .dispatch(request("place-order@1", List.of()));

        assertThat(invoked).hasValue(0);
        assertThat(sink.completed).isEmpty();
        assertThat(sink.failed).isEmpty();
    }

    @Test
    void a_cancellation_during_the_task_suppresses_the_completion() {
        TaskHandler<OrderInput, OrderOutput> handler = (in, ctx) -> new OrderOutput("ok", 1);
        var registry = new TaskRegistry(List.of(registration(handler)));
        var sink = new RecordingSink();

        // false before start, true before reply
        dispatcher(registry, sink, new ProgrammableCancellations(false, true), false)
                .dispatch(request("place-order@1", List.of()));

        assertThat(sink.completed).isEmpty();
        assertThat(sink.failed).isEmpty();
    }

    @Test
    void an_unknown_task_is_ignored_unless_strict() {
        var registry = new TaskRegistry(List.of());
        var lenient = new RecordingSink();
        dispatcher(registry, lenient, Cancellations.NONE, false).dispatch(request("ghost@1", List.of()));
        assertThat(lenient.failed).isEmpty();

        var strict = new RecordingSink();
        dispatcher(registry, strict, Cancellations.NONE, true).dispatch(request("ghost@1", List.of()));
        assertThat(strict.failed).hasSize(1);
        assertThat(strict.failed.get(0)).contains("ghost@1");
    }

    @Test
    void a_refused_reply_propagates_for_redelivery() {
        TaskHandler<OrderInput, OrderOutput> handler = (in, ctx) -> new OrderOutput("ok", 1);
        var registry = new TaskRegistry(List.of(registration(handler)));
        TaskReplySink refusing = new TaskReplySink() {
            public void running(TaskExecutionRequested task) {}

            public void completed(TaskExecutionRequested task, List<Variable> variables) {
                throw new ReplyNotAcceptedException("broker down");
            }

            public void failed(TaskExecutionRequested task, List<Variable> variables, String reason) {}
        };

        assertThatThrownBy(() -> dispatcher(registry, refusing, Cancellations.NONE, false)
                .dispatch(request("place-order@1", List.of())))
                .isInstanceOf(ReplyNotAcceptedException.class);
    }

    @Test
    void the_context_exposes_the_task_and_progress_reports_running() {
        var seen = new Object() {
            String pid;
            String step;
            boolean cancelled;
        };
        TaskHandler<OrderInput, OrderOutput> handler = (in, ctx) -> {
            seen.pid = ctx.processId();
            seen.step = ctx.stepId();
            seen.cancelled = ctx.isCancelled();
            ctx.progress("halfway");
            return new OrderOutput("ok", 1);
        };
        var registry = new TaskRegistry(List.of(registration(handler)));
        var sink = new RecordingSink();

        dispatcher(registry, sink, Cancellations.NONE, false).dispatch(request("place-order@1", List.of()));

        assertThat(seen.pid).isEqualTo("p-1");
        assertThat(seen.step).isEqualTo("step-a");
        assertThat(seen.cancelled).isFalse();
        assertThat(sink.running).hasSize(1);
        assertThat(sink.completed).hasSize(1);
    }

    @Test
    void resolution_falls_back_to_step_id_and_rejects_duplicates() {
        TaskHandler<OrderInput, OrderOutput> handler = (in, ctx) -> new OrderOutput("ok", 1);
        var reg = new TaskRegistration<>("place-order", 1, "t", OrderInput.class, OrderOutput.class, handler);
        var registry = new TaskRegistry(List.of(reg));
        // ref() = "place-order@1", the registry key. Primary match is by taskId.
        assertThat(registry.resolve("place-order@1", "whatever")).isSameAs(reg);
        // A blank taskId (an ACTION with no contract) falls back to the stepId as the key.
        assertThat(registry.resolve("", "place-order@1")).isSameAs(reg);
        // Neither matches → no handler.
        assertThat(registry.resolve("ghost@1", "step-x")).isNull();

        assertThatThrownBy(() -> new TaskRegistry(List.of(reg, reg)))
                .isInstanceOf(IllegalStateException.class);
    }
}
