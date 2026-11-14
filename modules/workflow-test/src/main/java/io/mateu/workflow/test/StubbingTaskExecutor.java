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
 *   <li>an {@code HTTP_CALL} / {@code LLM_CALL} / {@code AGENT} step — by its {@link HttpStub} /
 *       {@link LlmStub} / {@link AgentStub}, or failed (finally) when it has none: a test never reaches the
 *       network, a model or an agent runtime;</li>
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
    private final Map<String, AgentStub> agentStubs = new ConcurrentHashMap<>();
    // AGENT invocations the runtime put under review, by step execution id, and the state sequence of each.
    private final Map<String, TaskExecutionRequested> underReview = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, java.util.concurrent.atomic.AtomicLong> agentSequences = new ConcurrentHashMap<>();
    private final List<TaskInvocation> invocations = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> counts = new ConcurrentHashMap<>();
    // By step execution id, in dispatch order: a multi-instance step parks one task per instance.
    private final Map<String, TaskExecutionRequested> parked = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile Set<String> foreignProcesses = Set.of();
    // What a stub got wrong (an LLM answer its step's responseSchema refuses …): the test fails on it.
    private final List<String> stubProblems = new CopyOnWriteArrayList<>();
    // The task ids the engine gives its built-in tasks (complete-form …): a step of such a type names no task.
    private static final Set<String> BUILT_IN_TASK_IDS = java.util.Arrays.stream(
                    io.mateu.workflow.domain.aggregates.StepType.values())
            .map(io.mateu.workflow.domain.services.BuiltInTasks::taskIdOf)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

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
        agentStubs.clear();
        underReview.clear();
        agentSequences.clear();
        invocations.clear();
        counts.clear();
        parked.clear();
        stubProblems.clear();
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
        return llmStubs.computeIfAbsent(stepId, id -> new LlmStub(id, stubProblems::add));
    }

    AgentStub agentStub(String stepId) {
        return agentStubs.computeIfAbsent(stepId, AgentStub::new);
    }

    List<TaskInvocation> invocations() {
        return List.copyOf(invocations);
    }

    /** What the stubs of this test got wrong, in the order it happened; empty when nothing. */
    List<String> stubProblems() {
        return List.copyOf(stubProblems);
    }

    void clearStubProblems() {
        stubProblems.clear();
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
        var step = stepOf(request);
        var invocation = new TaskInvocation(request.processId(), request.workflowDefinitionId(), request.stepId(),
                request.taskId(), step == null ? null : step.topic(), request.attempt(), counts.merge(key, 1, Integer::sum),
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
                    : () -> httpStubs.get(request.stepId()).answer(Values.tree(invocation.variable("__http")),
                            iterationOf(step, invocation)), "HTTP_CALL",
                    "whenHttpCall");
        } else if ("run-agent".equals(base) && agentStubs.containsKey(request.stepId())) {
            answerAgent(request, agentStubs.get(request.stepId()).invoked(Values.tree(invocation.variable("__agent"))), false);
            return;
        } else if ("run-agent".equals(base)) {
            outcome = builtIn(request, invocation, null, "AGENT", "whenAgent");
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
        if (stub == null) {
            // whenTask(stepId): the step names no task of its own — the engine gave it a built-in one.
            stub = taskStubs.get(request.stepId());
        }
        if (stub != null) {
            return stub.answer(invocation);
        }
        return TaskOutcome.fail(kind + "_NOT_STUBBED", kind + " step '" + request.stepId()
                + "' has no stub, and a test does not reach the network: runtime." + method + "(\""
                + request.stepId() + "\")", false);
    }

    void reply(TaskExecutionRequested request, TaskOutcome outcome) {
        // The attempt and an AGENT task's invocation are echoed, as WorkerReply does: the engine drops an
        // agent reply that does not name its invocation.
        switch (outcome) {
            case TaskOutcome.Complete complete -> updateStepExecution.getObject().handle(new UpdateStepExecutionCommand(
                    request.taskExecutionId(), Values.domainVariables(complete.variables()), "",
                    StepExecutionStatus.COMPLETED, false, request.attempt(), request.invocationId(), false, null));
            case TaskOutcome.Fail fail -> updateStepExecution.getObject().handle(new UpdateStepExecutionCommand(
                    request.taskExecutionId(), List.of(), fail.reason(), StepExecutionStatus.ERROR, false,
                    request.attempt(), request.invocationId(), false, null));
            case TaskOutcome.NoReply ignored -> parked.put(request.taskExecutionId(), request);
        }
    }

    /**
     * The runtime's answer to an AGENT invocation — {@code resolving} a review, or the first answer. Each reply
     * carries the invocation id and a state sequence one past the previous, as the agent runtime's do.
     */
    void answerAgent(TaskExecutionRequested request, AgentStub.Answer answer, boolean resolving) {
        var update = updateStepExecution.getObject();
        var sequence = agentSequences.computeIfAbsent(request.taskExecutionId(), id -> new java.util.concurrent.atomic.AtomicLong());
        java.util.function.BiConsumer<StepExecutionStatus, List<io.mateu.workflow.domain.aggregates.Variable>> send =
                (status, variables) -> update.handle(new UpdateStepExecutionCommand(request.taskExecutionId(), variables,
                        "", status, false, request.attempt(), request.invocationId(), resolving, sequence.incrementAndGet()));
        if (resolving && !(answer instanceof AgentStub.ReviewRequired)) {
            // A review the runtime asked for resumes with RUNNING (a hold after a cancel or a timeout ignores it
            // and waits for the outcome below).
            send.accept(StepExecutionStatus.RUNNING, List.of());
        }
        switch (answer) {
            case AgentStub.Output output -> {
                var outputVariable = Values.tree(variableOf(request, "__agent")).path("outputVariable").asText("");
                send.accept(StepExecutionStatus.COMPLETED,
                        List.of(new io.mateu.workflow.domain.aggregates.Variable(outputVariable, output.json())));
            }
            case AgentStub.Failure failure -> {
                var reason = failure.message() == null ? failure.code() : failure.code() + ": " + failure.message();
                update.handle(new UpdateStepExecutionCommand(request.taskExecutionId(), List.of(),
                        io.mateu.workflow.worker.FailureMarkers.nonRetryable(reason), StepExecutionStatus.ERROR, true,
                        request.attempt(), request.invocationId(), resolving, sequence.incrementAndGet()));
            }
            case AgentStub.ReviewRequired ignored -> {
                underReview.put(request.taskExecutionId(), request);
                send.accept(StepExecutionStatus.REVIEW_REQUIRED, List.of());
            }
            case AgentStub.Cancelled ignored -> send.accept(StepExecutionStatus.CANCELLED, List.of());
            case AgentStub.NoReply ignored -> parked.put(request.taskExecutionId(), request);
        }
    }

    /**
     * The held AGENT invocation of {@code stepId} in {@code processId} — put under review by the runtime, or
     * left without an answer and then held by the engine (a timeout, a cancel) — removed; null when none is.
     */
    TaskExecutionRequested unholdAgent(String processId, String stepId) {
        synchronized (underReview) {
            var found = underReview.values().stream()
                    .filter(request -> processId.equals(request.processId()) && stepId.equals(request.stepId()))
                    .findFirst().orElse(null);
            if (found != null) {
                underReview.remove(found.taskExecutionId());
                return found;
            }
        }
        return unpark(processId, stepId);
    }

    private static String variableOf(TaskExecutionRequested request, String name) {
        return request.variables() == null ? null : request.variables().stream()
                .filter(variable -> name.equals(variable.name())).map(io.mateu.workflow.dtos.Variable::value)
                .findFirst().orElse(null);
    }

    private TaskStub stubFor(TaskExecutionRequested request, String base, String topic) {
        var stub = stepStubs.get(request.stepId());
        if (stub == null && (base.isBlank() || BUILT_IN_TASK_IDS.contains(base))) {
            // As the worker SDK resolves a handler: by step id when the step names no task — and a built-in
            // task's step (a USER_TASK's complete-form …) names none either: the engine gave it its id.
            stub = taskStubs.get(request.stepId());
        }
        if (stub == null && !base.isBlank()) {
            stub = taskStubs.get(base);
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

    /**
     * The forEach iteration a dispatch of a step with a {@code forEach} belongs to — its index and item, read
     * from the variables the iteration was sent — or null for any other dispatch.
     */
    private static HttpStub.Iteration iterationOf(io.mateu.workflow.domain.aggregates.Step step, TaskInvocation invocation) {
        var forEach = step == null ? null : step.forEach();
        if (forEach == null) {
            return null;
        }
        Integer index;
        try {
            index = Integer.valueOf(String.valueOf(invocation.variable(forEach.indexVariable())).trim());
        } catch (NumberFormatException notAnIteration) {
            return null;
        }
        var item = invocation.variable(forEach.itemVariable());
        return new HttpStub.Iteration(index, item == null ? null : Values.parsed(item));
    }

    private io.mateu.workflow.domain.aggregates.Step stepOf(TaskExecutionRequested request) {
        try {
            return definitions.findById(request.workflowDefinitionId())
                    // A step of a PROCESS step's inline body: its definition is built from the parent's.
                    .or(() -> io.mateu.workflow.domain.services.EmbeddedProcessDefinitions
                            .withEmbedded(definitions.findAll()).stream()
                            .filter(definition -> request.workflowDefinitionId().equals(definition.id()))
                            .findFirst())
                    .flatMap(definition -> definition.steps() == null ? java.util.Optional.empty()
                            : definition.steps().stream().filter(step -> request.stepId().equals(step.id())).findFirst())
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
