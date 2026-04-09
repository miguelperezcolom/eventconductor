package io.mateu.workflow.domain.aggregates;

import io.mateu.uidl.annotations.*;
import io.mateu.uidl.interfaces.Identifiable;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

@FormLayout(columns = 4)
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
        String description,
        @Section(value = "Precondition", style = "width: 25%;")
        @HiddenInList
        StepPrecondition precondition,
        @Section(value = "Execution", style = "width: 25%;")
        boolean parallel,
        @HiddenInList
        String topic,
        @HiddenInList
        String formId,
        @Section(value = "Reliability", style = "width: 25%;")
        @HiddenInList
        boolean rollbackable,
        @HiddenInList
        long timeout,
        @HiddenInList
        int retries,
        @HiddenInList
        @Hidden("!state['steps-rollbackable']")
        String compensationStepId
) implements Identifiable {
}
