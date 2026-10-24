package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The tasks the platform serves itself: the step types whose work is a task like any worker's
 * (USER_TASK, RULE, HTTP_CALL, LLM_CALL, AGENT), with the task id the engine gives them and the topic
 * they go to when the step names none. The orchestration knows a step becomes a task; which task,
 * and where it goes, is read here rather than decided type by type across the engine.
 */
public final class BuiltInTasks {

    /**
     * A built-in task.
     *
     * @param taskId       what the engine dispatches it as
     * @param defaultTopic where it goes when the step names no topic; null for the engine's default one
     * @param topicFixed   whether a step's own {@code topic} is ignored (AGENT: its runtime's topic)
     */
    public record Task(String taskId, String defaultTopic, boolean topicFixed) {
    }

    private static final Map<StepType, Task> TASKS = new EnumMap<>(StepType.class);

    static {
        TASKS.put(StepType.USER_TASK, new Task("complete-form", null, false));
        TASKS.put(StepType.RULE, new Task("evaluate-rule", null, false));
        // HTTP and model egress on topics of their own, so they can be scaled and network-policed apart.
        TASKS.put(StepType.HTTP_CALL, new Task(HttpRequestRenderer.TASK_ID, HttpRequestRenderer.DEFAULT_TOPIC, false));
        TASKS.put(StepType.LLM_CALL, new Task(LlmRequestRenderer.TASK_ID, LlmRequestRenderer.DEFAULT_TOPIC, false));
        TASKS.put(StepType.AGENT, new Task(AgentRequestRenderer.TASK_ID, AgentRequestRenderer.TOPIC, true));
    }

    private BuiltInTasks() {
    }

    /** The built-in task of a step type, if it is one. */
    public static Optional<Task> of(StepType type) {
        return type == null ? Optional.empty() : Optional.ofNullable(TASKS.get(type));
    }

    /** The task id of a built-in task's step type; null for any other. */
    public static String taskIdOf(StepType type) {
        return of(type).map(Task::taskId).orElse(null);
    }

    /**
     * The task id a step is dispatched with: a built-in task's own; for an ACTION, the task contract it
     * references ({@code <id>@<version>}, pinned at import), so a worker resolves its handler by
     * contract rather than by stepId; empty otherwise (the runtime falls back to stepId).
     */
    public static String taskIdOf(Step step) {
        var builtIn = taskIdOf(step.type());
        if (builtIn != null) {
            return builtIn;
        }
        return step.task() != null && !step.task().isBlank() ? step.task() : "";
    }

    /** The topic a step's task goes to: its own, unless its task's is fixed; else its task's default; null for the engine's default. */
    public static String topicOf(Step step) {
        var task = of(step.type()).orElse(null);
        var declared = step.topic() == null || step.topic().isBlank() ? null : step.topic();
        if (task == null) {
            return step.topic();
        }
        if (task.topicFixed() || declared == null) {
            return task.defaultTopic();
        }
        return declared;
    }

    /** Every built-in task id. */
    public static Set<String> taskIds() {
        return TASKS.values().stream().map(Task::taskId).collect(Collectors.toUnmodifiableSet());
    }

    /** The default topics of the built-in tasks that have one of their own. */
    public static Set<String> defaultTopics() {
        return TASKS.values().stream().map(Task::defaultTopic).filter(topic -> topic != null)
                .collect(Collectors.toUnmodifiableSet());
    }
}
