package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowStatus;

import java.util.List;

/**
 * The workflow a PROCESS step writes in line — its {@code steps:} body — as the definition its child
 * processes run.
 *
 * <p>An inline body is the same thing as a child workflow named by {@code childWorkflowDefinitionId},
 * except where it lives: in the parent's file, next to the step that runs it. So it runs exactly as a
 * named child does — one child process per run of the step, one per item under a {@code forEach} —
 * and all that differs is where the child's definition comes from. Not from the repository: there is
 * no row for it, which is what makes it impossible to start on its own (every door that starts a
 * process looks the id up there, and finds nothing). It is built here, from the parent's frozen
 * snapshot, when the child is created:
 * <ul>
 *   <li>its id is {@code <parent definition id>.<step id>} — a nested body composes, so a body inside a
 *       body inside {@code booking} is {@code booking.rooms.extras};</li>
 *   <li>its version is the parent's: the body is part of the parent's steps, so it is part of the
 *       parent's version and changes only when the parent does;</li>
 *   <li>it inherits what the parent declares about the data and who may touch it — sensitive variables,
 *       required scopes and roles, tags — because the child holds the parent's variables.</li>
 * </ul>
 */
public final class EmbeddedProcessDefinitions {

    /** Between the parent definition's id and the step's in an embedded definition's id. */
    public static final String SEPARATOR = ".";

    /** Whether {@code step} carries an inline body. */
    public static boolean hasInlineBody(Step step) {
        return step != null && step.steps() != null && !step.steps().isEmpty();
    }

    /** The id of the definition {@code step}'s inline body becomes, inside {@code parentDefinitionId}. */
    public static String idOf(String parentDefinitionId, String stepId) {
        return parentDefinitionId + SEPARATOR + stepId;
    }

    /**
     * The workflow a PROCESS step's child runs: the one it names, or — with an inline body — the one its
     * body becomes inside {@code parentDefinitionId}. Null when it does neither.
     */
    public static String childDefinitionIdOf(String parentDefinitionId, Step step) {
        if (step == null) {
            return null;
        }
        if (hasInlineBody(step)) {
            return idOf(parentDefinitionId, step.id());
        }
        return step.childWorkflowDefinitionId() == null || step.childWorkflowDefinitionId().isBlank()
                ? null : step.childWorkflowDefinitionId();
    }

    /**
     * The definition {@code step}'s inline body becomes, inside {@code parent} (the parent process's
     * snapshot, or the definition being validated).
     *
     * @throws IllegalArgumentException if the step has no inline body
     */
    public static WorkflowDefinition of(WorkflowDefinition parent, Step step) {
        if (!hasInlineBody(step)) {
            throw new IllegalArgumentException("Step '" + (step == null ? null : step.id())
                    + "' has no inline body.");
        }
        var id = idOf(parent.id(), step.id());
        List<Step> body = step.steps().stream()
                .map(inner -> inner == null ? null : inner.withWorkflowDefinitionId(id))
                .toList();
        var name = step.name() == null || step.name().isBlank() ? step.id() : step.name();
        return new WorkflowDefinition(id, name, parent.version(), step.description(),
                false, 0, false, null, parent.defaultMaxStepExecutions(), body,
                false, WorkflowStatus.ACTIVE, WorkflowStatus.ACTIVE,
                parent.requiredScopes(), parent.requiredRoles(), parent.maxSteps(), null,
                null, null, null,
                null, parent.tags(), null,
                null, parent.sensitiveVariables(),
                null);
    }

    /**
     * {@code definitions} and, after each, every definition its inline bodies become (nested ones
     * included) — for whatever scans the definitions for what their steps wait for or listen to, since a
     * step inside an inline body waits in a process of its own just as a top-level step does.
     */
    public static List<WorkflowDefinition> withEmbedded(java.util.Collection<WorkflowDefinition> definitions) {
        var all = new java.util.ArrayList<WorkflowDefinition>();
        if (definitions != null) {
            for (var definition : definitions) {
                addWithEmbedded(definition, all);
            }
        }
        return all;
    }

    private static void addWithEmbedded(WorkflowDefinition definition, List<WorkflowDefinition> all) {
        all.add(definition);
        if (definition == null || definition.steps() == null) {
            return;
        }
        for (var step : definition.steps()) {
            if (isInlineProcess(step)) {
                addWithEmbedded(of(definition, step), all);
            }
        }
    }

    /** Whether {@code step} is a PROCESS step whose child workflow is written in line. */
    public static boolean isInlineProcess(Step step) {
        return step != null && StepType.PROCESS.equals(step.type()) && hasInlineBody(step);
    }

    private EmbeddedProcessDefinitions() {
    }
}
