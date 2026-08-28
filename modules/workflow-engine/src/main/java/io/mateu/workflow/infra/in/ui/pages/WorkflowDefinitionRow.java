package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.uidl.data.Status;
import io.mateu.uidl.data.StatusType;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowStatus;

/**
 * One row of the definitions listing: what identifies a definition and whether it is running.
 *
 * <p>The listing used to paint {@link WorkflowDefinition} itself, which meant sixteen columns —
 * concurrency limits, cron expression, step counts, required scopes and roles, two separate status
 * fields — for a page whose job is to let you find a definition and open it. Everything dropped is
 * still on the detail view, which is where a person goes to read it.
 */
public record WorkflowDefinitionRow(String id, String name, String description, Status status)
        implements Identifiable {

    /**
     * The lifecycle in one badge, in the order that decides what actually happens to a new
     * instance.
     *
     * <p>Archived and disabled both refuse new instances, and paused holds the ones already
     * running — so a definition that is both archived and paused is reported as archived, the
     * stronger of the two. Reporting the pause instead would suggest that un-pausing brings it
     * back, and it does not.
     */
    public static WorkflowDefinitionRow of(WorkflowDefinition definition) {
        return new WorkflowDefinitionRow(definition.id(), definition.name(),
                definition.description(), statusOf(definition));
    }

    private static Status statusOf(WorkflowDefinition definition) {
        var lifecycle = definition.status();
        if (lifecycle == WorkflowStatus.ARCHIVED) {
            return new Status(StatusType.NONE, "Archived");
        }
        if (lifecycle == WorkflowStatus.DISABLED) {
            return new Status(StatusType.DANGER, "Disabled");
        }
        if (definition.paused()) {
            return new Status(StatusType.WARNING, "Paused");
        }
        return new Status(StatusType.SUCCESS, "Active");
    }
}
