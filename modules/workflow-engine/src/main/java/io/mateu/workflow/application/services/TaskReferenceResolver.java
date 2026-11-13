package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.TaskContractRepository;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.tasks.TaskContract;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Pins the task reference of every ACTION step when a workflow definition is imported, so a
 * definition never changes contract without someone editing the file: a bare {@code task: <id>} is
 * resolved to {@code <id>@<latest>}, and a step with no {@code topic} of its own inherits the
 * contract's default topic. A {@code task: <id>@<version>} is left as written (only checked to
 * exist). Called from every workflow import path before the definition is saved.
 *
 * <p>An unknown task id (or a pinned version that was never imported) fails the import loud rather
 * than silently: a step that would dispatch to a contract nobody serves is a misconfiguration, and
 * the same check the Maven plugin makes at build time is enforced here at load time.
 */
@Service
@RequiredArgsConstructor
public class TaskReferenceResolver {

    private final TaskContractRepository taskContractRepository;

    /** Returns a copy of {@code definition} with every ACTION step's task reference pinned. */
    public WorkflowDefinition resolve(WorkflowDefinition definition) {
        if (definition == null || definition.steps() == null || definition.steps().isEmpty()) {
            return definition;
        }
        if (!anyTask(definition.steps())) {
            return definition; // nothing references a contract — no lookups, no change
        }
        var resolved = definition.steps().stream().map(step -> resolveStep(definition, step)).toList();
        return definition.withSteps(resolved);
    }

    /** Whether any of {@code steps} — those of a PROCESS step's inline body included — references a task. */
    private static boolean anyTask(java.util.List<Step> steps) {
        return steps != null && steps.stream().anyMatch(step -> step != null
                && (StepType.ACTION.equals(step.type()) && step.task() != null && !step.task().isBlank()
                || anyTask(step.steps())));
    }

    private Step resolveStep(WorkflowDefinition definition, Step original) {
        // An inline body's tasks are pinned as the definition loads, as its own steps' are.
        final Step step = original.steps() == null || original.steps().isEmpty() ? original
                : original.withSteps(original.steps().stream().map(inner -> resolveStep(definition, inner)).toList());
        if (!StepType.ACTION.equals(step.type()) || step.task() == null || step.task().isBlank()) {
            return step;
        }
        var ref = step.task().trim();
        var at = ref.indexOf('@');
        var id = at >= 0 ? ref.substring(0, at) : ref;
        Integer version = null;
        if (at >= 0) {
            try {
                version = Integer.parseInt(ref.substring(at + 1));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Step '" + step.id() + "' in workflow '" + definition.id()
                        + "' has a malformed task reference '" + ref + "' (expected <id> or <id>@<version>).");
            }
        }
        TaskContract contract = (version == null
                ? taskContractRepository.findLatest(id)
                : taskContractRepository.find(id, version))
                .orElseThrow(() -> new IllegalStateException("Step '" + step.id() + "' in workflow '"
                        + definition.id() + "' references unknown task '" + ref + "'."));
        var pinned = step.withTask(contract.ref());
        // The contract's topic is the default; an explicit topic on the step wins.
        if ((step.topic() == null || step.topic().isBlank())
                && contract.topic() != null && !contract.topic().isBlank()) {
            pinned = pinned.withTopic(contract.topic());
        }
        return pinned;
    }
}
