package io.mateu.workflow.domain.aggregates;

import io.mateu.core.infra.declarative.Entity;
import io.mateu.uidl.annotations.Colspan;
import io.mateu.uidl.annotations.DetailFormCustomisation;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.data.FormPosition;

import java.util.List;

@FormLayout(columns = 5)
public record WorkflowDefinition(
        String id,
        String name,
        @Hidden
        int version,
        String description,
        WorkflowDefinitionStatus status,
        @Colspan(5)
        @DetailFormCustomisation(position = FormPosition.modalRight, style = "display: block; min-width: 90rem;")
        List<Step> steps
) implements Entity<String> {
}
