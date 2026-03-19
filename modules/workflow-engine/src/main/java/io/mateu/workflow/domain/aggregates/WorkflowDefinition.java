package io.mateu.workflow.domain.aggregates;

import io.mateu.core.infra.valuegenerators.UUIDValueGenerator;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.FormPosition;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.uidl.interfaces.Searchable;

import java.util.List;

@FormLayout(columns = 5)
public record WorkflowDefinition(
        @GeneratedValue(UUIDValueGenerator.class)
        @HiddenInCreate
        String id,
        String name,
        @Hidden
        int version,
        String description,
        WorkflowDefinitionStatus status,
        @Colspan(5)
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
}
