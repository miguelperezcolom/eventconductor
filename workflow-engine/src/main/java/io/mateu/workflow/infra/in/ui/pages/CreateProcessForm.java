package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.declarative.Form;
import io.mateu.uidl.annotations.Button;
import io.mateu.uidl.annotations.ForeignKey;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.MasterDetail;
import io.mateu.workflow.application.usecases.process.create.CreateProcessCommand;
import io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.infra.in.ui.pages.workflowdefinition.WorkflowDefinitionIdLabelSupplier;
import io.mateu.workflow.infra.in.ui.pages.workflowdefinition.WorkflowDefinitionIdOptionsSupplier;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@FormLayout(columns = 1)
public class CreateProcessForm extends Form {

    final CreateProcessUseCase createProcessUseCase;

    @ForeignKey(search = WorkflowDefinitionIdOptionsSupplier.class, label = WorkflowDefinitionIdLabelSupplier.class)
    @NotNull
    @Label("Workflow Definition")
    String workflowDefinitionId = "d1";

    String businessKey;

    @MasterDetail(minHeightWhenDetailVisible = "16rem;")
    List<Variable> variables;

    @Button
    URI create() {
        var processId = UUID.randomUUID().toString();
        createProcessUseCase.handle(new CreateProcessCommand(
                processId,
                workflowDefinitionId,
                businessKey,
                variables
        ));
        return URI.create("/processes/" + processId);
    }

}
