package io.mateu.workflow.domain;

import io.mateu.core.infra.valuegenerators.UUIDValueGenerator;
import io.mateu.uidl.annotations.*;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.infra.in.ui.suppliers.FormIdLabelSupplier;
import io.mateu.workflow.infra.in.ui.suppliers.FormIdOptionsSupplier;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@Style("width: 100%;")
public record FormExecution(
        @GeneratedValue(UUIDValueGenerator.class)
        @HiddenInCreate
        String id,
        @NotEmpty
        @ForeignKey(search = FormIdOptionsSupplier.class, label = FormIdLabelSupplier.class)
        String formId,
        String processId,
        String stepId,
        String stepExecutionId,
        List<Variable> variables,
        List<Value> values,
        @NotNull
        FormExecutionStatus status,
        String userId,
        String userGroup
) implements Identifiable {

    @Override
    public String toString() {
        return id != null?id:"New form execution";
    }
}
