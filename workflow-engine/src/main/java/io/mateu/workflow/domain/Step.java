package io.mateu.workflow.domain;

import io.mateu.core.infra.declarative.Entity;
import io.mateu.uidl.annotations.Colspan;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.HiddenInList;

@FormLayout(columns = 4)
public record Step(
        String id,
        @Hidden
        String workflowDefinitionId,
        StepType type,
        String name,
        String description,
        @Colspan(4)
        @HiddenInList
        StepPrecondition precondition,
        @HiddenInList
        String formId,
        @HiddenInList
        boolean rollbackable,
        @HiddenInList
        long timeout,
        @HiddenInList
        int retries,
        @HiddenInList
        String compensationStepId
) implements Entity<String> {
}
