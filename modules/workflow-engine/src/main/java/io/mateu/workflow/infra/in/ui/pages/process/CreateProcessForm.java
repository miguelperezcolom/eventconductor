package io.mateu.workflow.infra.in.ui.pages.process;

import io.mateu.uidl.annotations.*;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.data.*;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.usecases.process.create.CreateProcessCommand;
import io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.infra.in.ui.WorkflowHome;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdLabelSupplier;
import io.mateu.workflow.infra.in.ui.suppliers.WorkflowDefinitionIdOptionsSupplier;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
@FormLayout(columns = 1)
public class CreateProcessForm {

    final CreateProcessUseCase createProcessUseCase;

    @Lookup(search = WorkflowDefinitionIdOptionsSupplier.class, label = WorkflowDefinitionIdLabelSupplier.class)
    @NotNull
    @Label("Workflow Definition")
    String workflowDefinitionId;

    String businessKey;

    @MasterDetail(minHeightWhenDetailVisible = "16rem;")
    List<Variable> variables;

    @Toolbar(buttonStyle = ButtonStyle.primary)
    Object create(HttpRequest httpRequest) {
        var processId = UUID.randomUUID().toString();
        createProcessUseCase.handle(new CreateProcessCommand(
                processId,
                workflowDefinitionId,
                businessKey,
                variables,
                null
        ));
        // Creating IS this form's save: clear the dirty flag before navigating away, or the
        // frontend asks whether to save the changes that have just been persisted.
        return List.of(
                UICommand.markAsClean(),
                UICommand.builder()
                        .type(UICommandType.DispatchEvent)
                        .data(new DispatchEventData(
                                "navigation-requested",
                                NavigationRequestedPayload.builder()
                                        .route("/workflow/processes/" + processId)
                                        .consumedRoute("")
                                        .baseUrl(httpRequest.getBaseUrl())
                                        .uriPrefix("")
                                        .serverSideType(WorkflowHome.class.getName())
                                        .build()
                        ))
                        .build());
    }

}
