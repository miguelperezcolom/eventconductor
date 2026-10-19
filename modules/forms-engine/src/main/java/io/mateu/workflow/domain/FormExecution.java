package io.mateu.workflow.domain;

import io.mateu.core.infra.valuegenerators.UUIDValueGenerator;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.StatusType;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.infra.in.ui.suppliers.FormIdLabelSupplier;
import io.mateu.workflow.infra.in.ui.suppliers.FormIdOptionsSupplier;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.With;

import java.time.LocalDateTime;
import java.util.List;

@Style("width: 100%;")
@With
@Builder
@FormLayout(columns = 4)
public record FormExecution(
        @GeneratedValue(UUIDValueGenerator.class)
        @HiddenInCreate
        String id,
        @NotEmpty
        @Lookup(search = FormIdOptionsSupplier.class, label = FormIdLabelSupplier.class)
        String formId,
        String processId,
        String stepId,
        @HiddenInList
        String stepExecutionId,
        @NotNull
        // The mapping matches on the enum's name, which is upper-case — PENDING, ASSIGNED,
        // COMPLETED, CANCELLED. The earlier "Assigned"/"Completed" were title-case and matched
        // nothing, so every status fell through to the grey default. And CANCELLED had no mapping
        // at all, so a cancelled task looked no different from a pending one.
        @Status(defaultStatus = StatusType.NONE, mappings = {
                @StatusMapping(from = "PENDING", to = StatusType.INFO),
                @StatusMapping(from = "ASSIGNED", to = StatusType.WARNING),
                @StatusMapping(from = "COMPLETED", to = StatusType.SUCCESS),
                @StatusMapping(from = "CANCELLED", to = StatusType.DANGER),
        })
        FormExecutionStatus status,
        String userId,
        String userGroup,
        @Colspan(2)
        List<Variable> variables,
        @Colspan(2)
        List<Value> values,
        /* Assignment (see HumanTaskAssignment): the assignee is userId — who holds the task. */
        @HiddenInList
        List<String> candidateUsers,
        @HiddenInList
        List<String> candidateGroups,
        /* 0-100, higher is more urgent; null reads as the default, 50. */
        Integer priority,
        /* When it is due, UTC. Past it, an open task is overdue. */
        LocalDateTime dueDate,
        /* When someone should look at it again, UTC. */
        @HiddenInList
        LocalDateTime followUpDate,
        @HiddenInList
        String businessKey,
        /* When the task was created, UTC. */
        @HiddenInList
        LocalDateTime createdAt,
        /* How many of its escalations fired, and when the last one did (UTC). */
        @HiddenInList
        Integer escalationLevel,
        @HiddenInList
        LocalDateTime escalatedAt,
        /*
         * Whether the task named who it is for when it was created (an assignee or candidates). A
         * restricted task is visible only to them and to task admins — also after its assignee
         * releases it; an unrestricted one is anyone's who may work on its form, as every task was
         * before assignment existed.
         */
        @HiddenInList
        Boolean restricted
) implements Identifiable {

    public FormExecution {
        candidateUsers = candidateUsers == null ? List.of() : List.copyOf(candidateUsers);
        candidateGroups = candidateGroups == null ? List.of() : List.copyOf(candidateGroups);
    }

    @Override
    public String toString() {
        return id != null?id:"New form execution";
    }

    /** Whether the task is still waiting for someone: PENDING or ASSIGNED. */
    public boolean isOpen() {
        return FormExecutionStatus.PENDING.equals(status) || FormExecutionStatus.ASSIGNED.equals(status);
    }

    /** Whether somebody holds it. */
    public boolean isAssigned() {
        return userId != null && !userId.isBlank();
    }

    /** Its priority, the default for none. */
    public int priorityOrDefault() {
        return priority == null ? io.mateu.workflow.dtos.humantasks.HumanTaskAssignment.DEFAULT_PRIORITY : priority;
    }

    /** Whether it is open and past its due date at {@code nowUtc}. */
    public boolean isOverdue(LocalDateTime nowUtc) {
        return isOpen() && dueDate != null && dueDate.isBefore(nowUtc);
    }

    /** Whether it is restricted to the people it names (null — a task from before — is not). */
    public boolean isRestricted() {
        return Boolean.TRUE.equals(restricted);
    }

    /** How many of its escalations fired. */
    public int escalations() {
        return escalationLevel == null ? 0 : escalationLevel;
    }
}
