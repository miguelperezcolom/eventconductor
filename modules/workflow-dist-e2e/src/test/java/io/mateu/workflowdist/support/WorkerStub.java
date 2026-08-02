package io.mateu.workflowdist.support;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import org.springframework.cloud.stream.function.StreamBridge;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Static registry backing the worker context. The whole suite runs in one JVM, so tests
 * program behaviors here and the (separate) worker Spring context reads them — the
 * worker-side observation point the DIST specs assert on (execution counts per step).
 *
 * <p>Behaviors are keyed by {@code workflowDefinitionId/stepId}, falling back to
 * {@code workflowDefinitionId}, falling back to immediate completion. They must not block:
 * a behavior that needs to wait for the test records the request and stays silent, and the
 * test replies later via {@link #complete(TaskExecutionRequested)}.
 */
public final class WorkerStub {

    /** What the worker does when it receives a task. Runs on the Kafka consumer thread. */
    public interface Behavior {
        void onTask(TaskExecutionRequested request, int invocation);
    }

    private static volatile StreamBridge streamBridge;
    private static final Map<String, Behavior> behaviors = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> executionCounts = new ConcurrentHashMap<>();
    private static final List<TaskExecutionRequested> received = new CopyOnWriteArrayList<>();

    private WorkerStub() {
    }

    static void attach(StreamBridge bridge) {
        streamBridge = bridge;
    }

    static void onTask(TaskExecutionRequested request) {
        received.add(request);
        int invocation = executionCounts
                .computeIfAbsent(countKey(request.processId(), request.stepId()), k -> new AtomicInteger())
                .incrementAndGet();
        behaviors.getOrDefault(request.workflowDefinitionId() + "/" + request.stepId(),
                        behaviors.getOrDefault(request.workflowDefinitionId(), WorkerStub::completeNow))
                .onTask(request, invocation);
    }

    /** Programs the behavior for one step of one definition. */
    public static void on(String workflowDefinitionId, String stepId, Behavior behavior) {
        behaviors.put(workflowDefinitionId + "/" + stepId, behavior);
    }

    /** Behavior: report COMPLETED immediately, echoing the task's variables. */
    public static void completeNow(TaskExecutionRequested request, int invocation) {
        complete(request);
    }

    /** Behavior: stay silent (simulates a worker that died mid-task) — the test or a retry moves things on. */
    public static void silent(TaskExecutionRequested request, int invocation) {
    }

    /** Reports COMPLETED for a task, adding the given variables to the ones the task carried. */
    public static void complete(TaskExecutionRequested request, Variable... extraVariables) {
        var variables = new java.util.ArrayList<>(request.variables());
        variables.addAll(List.of(extraVariables));
        sendStatus(request.taskExecutionId(), TaskStatus.COMPLETED, variables, request.processId());
    }

    /** Sends a raw status report on the upstream topic, exactly like a real worker. */
    public static void sendStatus(String taskExecutionId, TaskStatus status, List<Variable> variables) {
        sendStatus(taskExecutionId, status, variables, null);
    }

    /**
     * As above, echoing back the process so the reply is routed to the pod that owns it — what a
     * worker built against the current shared module does. Passing null exercises the fallback
     * for workers that do not.
     */
    public static void sendStatus(String taskExecutionId, TaskStatus status, List<Variable> variables,
                                  String processId) {
        streamBridge.send("upstream", new TaskStatusChanged(taskExecutionId, status, variables, processId));
    }

    /** How many times the worker executed the given step of the given process. */
    public static int executionCount(String processId, String stepId) {
        var counter = executionCounts.get(countKey(processId, stepId));
        return counter == null ? 0 : counter.get();
    }

    /** All requests received so far for the given process. */
    public static List<TaskExecutionRequested> receivedFor(String processId) {
        return received.stream().filter(r -> r.processId().equals(processId)).toList();
    }

    /** Resets programmed behaviors and recorded state between tests. */
    public static void reset() {
        behaviors.clear();
        executionCounts.clear();
        received.clear();
    }

    private static String countKey(String processId, String stepId) {
        return processId + "/" + stepId;
    }
}
