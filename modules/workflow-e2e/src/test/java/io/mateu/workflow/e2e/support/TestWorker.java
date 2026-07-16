package io.mateu.workflow.e2e.support;

import io.mateu.workflow.application.out.EmbeddedTaskExecutor;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Programmable embedded worker used by the e2e tests. It is the single
 * {@link EmbeddedTaskExecutor} the engine dispatches every task to; tests register a
 * {@link Behavior} per step id and inspect how many times each step was invoked.
 *
 * <p>In embedded + memory mode task execution is synchronous and reentrant: completing a
 * step here drives the engine forward on the same thread, so most scenarios run to
 * completion within a single {@code createProcess} call. Behaviors that do NOT respond
 * (see {@link #deferForever()}) leave the step in flight so timeout/cancellation paths can
 * be exercised.
 */
public class TestWorker implements EmbeddedTaskExecutor {

    /** What the worker does when it receives a task for a given step. */
    public interface Behavior {
        void run(TaskExecutionRequested request, WorkerCallback callback, int invocation);
    }

    /** Callback the worker uses to report back to the engine. */
    public interface WorkerCallback {
        void complete(List<Variable> variables);
        void complete();
        void fail();
    }

    private final UpdateStepExecutionUseCase updateStepExecution;
    private final Map<String, Behavior> behaviors = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> invocations = new ConcurrentHashMap<>();
    private final List<TaskExecutionRequested> received = new CopyOnWriteArrayList<>();

    public TestWorker(UpdateStepExecutionUseCase updateStepExecution) {
        this.updateStepExecution = updateStepExecution;
    }

    public void on(String stepId, Behavior behavior) {
        behaviors.put(stepId, behavior);
    }

    /** Resets registered behaviors and recorded invocations between tests. */
    public void clear() {
        behaviors.clear();
        invocations.clear();
        received.clear();
    }

    /** Domain variable a worker writes back onto the process. */
    public static Variable var(String name, String value) {
        return new Variable(name, value);
    }

    /** Behavior: succeed immediately, writing the given variables onto the process. */
    public static Behavior succeed(Variable... variables) {
        return (req, cb, invocation) -> cb.complete(List.of(variables));
    }

    /** Behavior: report ERROR every time (drives retries / failure). */
    public static Behavior fail() {
        return (req, cb, invocation) -> cb.fail();
    }

    /** Behavior: fail the first {@code failures} invocations, then succeed. */
    public static Behavior failThenSucceed(int failures) {
        return (req, cb, invocation) -> {
            if (invocation <= failures) {
                cb.fail();
            } else {
                cb.complete();
            }
        };
    }

    /** Behavior: never respond — leaves the step PENDING/RUNNING (for timeout/cancel tests). */
    public static Behavior deferForever() {
        return (req, cb, invocation) -> { /* no callback */ };
    }

    public int invocationsOf(String stepId) {
        var counter = invocations.get(stepId);
        return counter == null ? 0 : counter.get();
    }

    public List<TaskExecutionRequested> received() {
        return List.copyOf(received);
    }

    @Override
    public void execute(TaskExecutionRequested request) {
        received.add(request);
        int invocation = invocations.computeIfAbsent(request.stepId(), k -> new AtomicInteger()).incrementAndGet();
        var behavior = behaviors.getOrDefault(request.stepId(), succeed());
        behavior.run(request, callbackFor(request), invocation);
    }

    private WorkerCallback callbackFor(TaskExecutionRequested request) {
        return new WorkerCallback() {
            @Override
            public void complete(List<Variable> variables) {
                updateStepExecution.handle(new UpdateStepExecutionCommand(
                        request.taskExecutionId(), variables, "", StepExecutionStatus.COMPLETED));
            }

            @Override
            public void complete() {
                complete(List.of());
            }

            @Override
            public void fail() {
                updateStepExecution.handle(new UpdateStepExecutionCommand(
                        request.taskExecutionId(), List.of(), "boom", StepExecutionStatus.ERROR));
            }
        };
    }
}
