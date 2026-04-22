package io.mateu.workflow.domain.aggregates;

import io.mateu.core.infra.valuegenerators.UUIDValueGenerator;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.FormPosition;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.uidl.interfaces.Searchable;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@FormLayout(columns = 3)
public record WorkflowDefinition(
        //@GeneratedValue(UUIDValueGenerator.class)
        @HiddenInEditor
        String id,
        @NotEmpty
        String name,
        @Hidden
        int version,
        String description,
        @NotNull
        WorkflowDefinitionStatus status,
        boolean limitConcurrentExecutions,
        @Min(0)
        @Hidden("!state.limitConcurrentExecutions")
        int maxConcurrentExecutions,
        @Hidden("!state.limitConcurrentExecutions")
        boolean enqueueOnLimit,
        @Colspan(4)
        @DetailFormCustomisation(position = FormPosition.modalRight, style = "display: block; min-width: 90rem;")
        List<Step> steps
) implements Identifiable, Searchable {

    @Override
    public String toString() {
        return id != null?name:"New workflow definition";
    }

    @Override
    public String searchableText() {
        return name + " " + description;
    }

    @Override
    public int maxConcurrentExecutions() {
        return (limitConcurrentExecutions)?maxConcurrentExecutions:1;
    }

    @Override
    public List<Step> steps() {
        return steps != null?steps:List.of();
    }
}
