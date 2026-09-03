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
        List<Value> values
) implements Identifiable {

    @Override
    public String toString() {
        return id != null?id:"New form execution";
    }
}
