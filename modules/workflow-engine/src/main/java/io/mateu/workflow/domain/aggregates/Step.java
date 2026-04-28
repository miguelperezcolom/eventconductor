package io.mateu.workflow.domain.aggregates;

import io.mateu.uidl.annotations.*;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdLabelSupplier;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdOptionsSupplier;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.With;

@FormLayout(columns = 4)
@With
public record Step(
        @Section(value = "Main", style = "width: 25%;")
        @NotEmpty
        String id,
        @Hidden
        String workflowDefinitionId,
        @NotNull
        StepType type,
        @NotEmpty
        String name,
        @HiddenInList
        String description,
        @Section(value = "Precondition", style = "width: 25%;")
        @HiddenInList
        StepPrecondition precondition,
        @Section(value = "Execution", style = "width: 25%;")
        boolean parallel,
        @HiddenInList
        @Hidden("state['steps-type'] != 'ACTION'")
        String topic,
        @HiddenInList
        @Hidden("state['steps-type'] != 'USER_TASK'")
        String formId,
        @HiddenInList
        @Hidden("state['steps-type'] != 'PROCESS'")
        @Lookup(search = WorkflowDefinitionIdOptionsSupplier.class, label = WorkflowDefinitionIdLabelSupplier.class)
        String childWorkflowDefinitionId,
        @Section(value = "Reliability", style = "width: 25%;")
        @HiddenInList
        long timeout,
        @HiddenInList
        int retries,
        @HiddenInList
        boolean rollbackable,
        @HiddenInList
        @Hidden("!state['steps-rollbackable']")
        String compensationStepId
) implements Identifiable {
}
