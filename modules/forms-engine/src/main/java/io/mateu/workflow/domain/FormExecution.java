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
        String stepExecutionId,
        @NotNull
        @Status(defaultStatus = StatusType.NONE, mappings = {
                @StatusMapping(from = "", to = StatusType.NONE),
        })
        FormExecutionStatus status,
        String userId,
        String userGroup,
        @Colspan(2)
        List<Variable> variables,
        @Colspan(2)
        List<Value> values
) implements Identifiable {

    @Override
    public String toString() {
        return id != null?id:"New form execution";
    }
}
