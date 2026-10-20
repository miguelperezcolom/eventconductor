package io.mateu.workflow.test;

import io.mateu.workflow.application.out.EmbeddedTaskExecutor;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionCommand;
import io.mateu.workflow.application.usecases.stepexecution.update.UpdateStepExecutionUseCase;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.worker.api.TaskDispatcher;
import io.mateu.workflow.worker.api.TaskRegistry;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The engine's only worker during a test. Every task the engine dispatches lands here and is
 * answered, in this order of precedence:
 *
 * <ol>
 *   <li>an {@code HTTP_CALL} / {@code LLM_CALL} step — by its {@link HttpStub} / {@link LlmStub}, or
 *       failed (finally) when it has none: a test never reaches the network;</li>
 *   <li>a {@link TaskStub} for the step id ({@code whenStep}), then ({@code whenTask}) for the task id
 *       without its version — the step id when the step names no task, as the worker SDK resolves
 *       handlers — then for the step's topic;</li>
 *   <li>a real {@code TaskHandler} of the test application, when the worker SDK serves the task;</li>
 *   <li>otherwise the task is parked — a USER_TASK always is, unless mocked — and waits for
 *       {@link WorkflowTestRuntime#completeTask} / {@link WorkflowTestRuntime#completeUserTask}, as a
 *       real step waits for its worker.</li>
 * </ol>
 *
 * Tasks of processes that existed before the current test began are parked, never answered by its
 * mocks: a cached Spring context outlives a test, and its leftovers should not consume the next
 * test's answers.
 */
final class StubbingTaskExecutor implements EmbeddedTaskExecutor {

    private final ObjectProvider<UpdateStepExecutionUseCase> updateStepExecution;
    private final WorkflowDefinitionRepository definitions;
    private final ObjectProvider<TaskRegistry> taskRegistry;
    private final ObjectProvider<TaskDispatcher> taskDispatcher;

    private final Map<String, TaskStub> stepStubs = new ConcurrentHashMap<>();
    private final Map<String, TaskStub> taskStubs = new ConcurrentHashMap<>();
    private final Map<String, HttpStub> httpStubs = new ConcurrentHashMap<>();
    private final Map<String, LlmStub> llmStubs = new ConcurrentHashMap<>();
    private final List<TaskInvocation> invocations = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> counts = new ConcurrentHashMap<>();
    // By step execution id, in dispatch order: a multi-instance step parks one task per instance.
    private final Map<String, TaskExecutionRequested> parked = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile Set<String> foreignProcesses = Set.of();

    StubbingTaskExecutor(ObjectProvider<UpdateStepExecutionUseCase> updateStepExecution,
                         WorkflowDefinitionRepository definitions,
                         ObjectProvider<TaskRegistry> taskRegistry,
                         ObjectProvider<TaskDispatcher> taskDispatcher) {
        this.updateStepExecution = updateStepExecution;
        this.definitions = definitions;
        this.taskRegistry = taskRegistry;
        this.taskDispatcher = taskDispatcher;
    }

    /** Forgets every mock and every recorded dispatch; tasks of {@code foreign} processes are parked from now on. */
    void reset(Set<String> foreign) {
        stepStubs.clear();
        taskStubs.clear();
        httpStubs.clear();
        llmStubs.clear();
        invocations.clear();
        counts.clear();
        parked.clear();
        foreignProcesses = Set.copyOf(foreign);
    }

    TaskStub stepStub(String stepId) {
        return stepStubs.computeIfAbsent(stepId, id -> new TaskStub("Step '" + id + "'"));
    }

    TaskStub taskStub(String taskIdOrTopic) {
        return taskStubs.computeIfAbsent(baseTaskId(taskIdOrTopic), id -> new TaskStub("Task '" + id + "'"));
    }

    HttpStub httpStub(String stepId) {
        return httpStubs.computeIfAbsent(stepId, HttpStub::new);
    }

    LlmStub llmStub(String stepId) {
        return llmStubs.computeIfAbsent(stepId, LlmStub::new);
    }

    List<TaskInvocation> invocations() {
        return List.copyOf(invocations);
    }

    /** The oldest parked task of {@code stepId} in {@code processId}, removed; null when none waits. */
    TaskExecutionRequested unpark(String processId, String stepId) {
        synchronized (parked) {
            var found = parked.values().stream()
                    .filter(request -> processId.equals(request.processId()) && stepId.equals(request.stepId()))
                    .findFirst().orElse(null);
            if (found != null) {
                parked.remove(found.taskExecutionId());
            }
            return found;
        }
    }

    /** Every task waiting for a test to answer it. */
    List<TaskExecutionRequested> parked() {
        synchronized (parked) {
            return List.copyOf(parked.values());
        }
    }

    @Override
    public void execute(TaskExecutionRequested request) {
        var key = key(request.processId(), request.stepId());
        var invocation = new TaskInvocation(request.processId(), request.workflowDefinitionId(), request.stepId(),
                request.taskId(), topicOf(request), request.attempt(), counts.merge(key, 1, Integer::sum),
                variablesOf(request), io.mateu.workflow.time.EngineClock.now());
        invocations.add(invocation);
        // A new dispatch replaces whatever attempt was parked before it: that one's reply would be dropped.
        parked.remove(request.taskExecutionId());
        if (foreignProcesses.contains(request.processId())) {
            parked.put(request.taskExecutionId(), request);
            return;
        }
        var base = baseTaskId(request.taskId());
        TaskOutcome outcome;
        if ("http-call".equals(base)) {
            outcome = builtIn(request, invocation, httpStubs.get(request.stepId()) == null ? null
                    : () -> httpStubs.get(request.stepId()).answer(Values.tree(invocation.variable("__http"))), "HTTP_CALL",
                    "whenHttpCall");
        } else if ("llm-call".equals(base)) {
            outcome = builtIn(request, invocation, llmStubs.get(request.stepId()) == null ? null
                    : () -> llmStubs.get(request.stepId()).answer(Values.tree(invocation.variable("__llm"))), "LLM_CALL",
                    "whenLlmCall");
        } else {
            var stub = stubFor(request, base, invocation.topic());
            if (stub != null) {
                outcome = stub.answer(invocation);
            } else if (servedByAHandler(request)) {
                taskDispatcher.getObject().dispatch(request);
                return;
            } else {
                outcome = TaskOutcome.noReply();
            }
        }
        reply(request, outcome);
    }

    private interface Answer {
        TaskOutcome get();
    }

    private TaskOutcome builtIn(TaskExecutionRequested request, TaskInvocation invocation, Answer stubbed,
                                String kind, String method) {
        if (stubbed != null) {
            return stubbed.get();
        }
        var stub = stepStubs.get(request.stepId());
        if (stub != null) {
            return stub.answer(invocation);
        }
        return TaskOutcome.fail(kind + "_NOT_STUBBED", kind + " step '" + request.stepId()
                + "' has no stub, and a test does not reach the network: runtime." + method + "(\""
                + request.stepId() + "\")", false);
    }

    void reply(TaskExecutionRequested request, TaskOutcome outcome) {
        switch (outcome) {
            case TaskOutcome.Complete complete -> updateStepExecution.getObject().handle(new UpdateStepExecutionCommand(
                    request.taskExecutionId(), Values.domainVariables(complete.variables()), "",
                    StepExecutionStatus.COMPLETED, false, request.attempt()));
            case TaskOutcome.Fail fail -> updateStepExecution.getObject().handle(new UpdateStepExecutionCommand(
                    request.taskExecutionId(), List.of(), fail.reason(), StepExecutionStatus.ERROR, false,
                    request.attempt()));
            case TaskOutcome.NoReply ignored -> parked.put(request.taskExecutionId(), request);
        }
    }

    private TaskStub stubFor(TaskExecutionRequested request, String base, String topic) {
        var stub = stepStubs.get(request.stepId());
        if (stub == null) {
            // As the worker SDK resolves a handler: by task id, or by step id when the step names no task.
            stub = taskStubs.get(base.isBlank() ? request.stepId() : base);
        }
        if (stub == null && topic != null) {
            stub = taskStubs.get(topic);
        }
        return stub;
    }

    private boolean servedByAHandler(TaskExecutionRequested request) {
        var registry = taskRegistry.getIfAvailable();
        return registry != null && taskDispatcher.getIfAvailable() != null
                && registry.resolve(request.taskId(), request.stepId()) != null;
    }

    private String topicOf(TaskExecutionRequested request) {
        try {
            return definitions.findById(request.workflowDefinitionId())
                    .flatMap(definition -> definition.steps() == null ? java.util.Optional.empty()
                            : definition.steps().stream().filter(step -> request.stepId().equals(step.id())).findFirst())
                    .map(step -> step.topic())
                    .orElse(null);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static Map<String, String> variablesOf(TaskExecutionRequested request) {
        var variables = new LinkedHashMap<String, String>();
        if (request.variables() != null) {
            request.variables().forEach(variable -> variables.put(variable.name(), variable.value()));
        }
        return variables;
    }

    static String baseTaskId(String taskId) {
        if (taskId == null) {
            return "";
        }
        var trimmed = taskId.trim();
        var at = trimmed.lastIndexOf('@');
        return at > 0 && trimmed.substring(at + 1).chars().allMatch(Character::isDigit) ? trimmed.substring(0, at) : trimmed;
    }

    private static String key(String processId, String stepId) {
        return processId + '\u0000' + stepId;
    }
}
