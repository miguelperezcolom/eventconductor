package io.mateu.workflow.infra.in.ui.pages.process.operate;

import io.mateu.uidl.annotations.Button;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.Help;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.MasterDetail;
import io.mateu.uidl.annotations.Stereotype;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.domain.aggregates.Variable;
import jakarta.validation.constraints.NotEmpty;

import java.util.ArrayList;
import java.util.List;

/**
 * A plain view model rather than a Spring bean: Mateu only accepts back the types it may expose, and a
 * service-stereotyped class it did not route to is refused. Services are looked up when an action runs.
 *
 * The "Set variables" dialog of the process detail — see {@code SetProcessVariablesUseCase}. */
@FormLayout(columns = 1)
public class SetVariablesForm {

    @Hidden
    String processId;

    @Label("Variables to set")
    @MasterDetail(minHeightWhenDetailVisible = "12rem;")
    List<Variable> set = new ArrayList<>();

    @Label("Variables to remove")
    @Help("Names, separated by commas")
    String remove;

    @NotEmpty
    @Label("Reason")
    @Help("Mandatory: recorded with your name in the process's audit trail")
    @Stereotype(FieldStereotype.textarea)
    String reason;

    public SetVariablesForm forProcess(String processId) {
        this.processId = processId;
        return this;
    }

    @Button
    @Label("Apply")
    public Object apply() {
        try {
            OperationForms.bean(ProcessOperationsService.class).setVariables(processId, OperationForms.variables(set), OperationForms.names(remove), reason,
                    OperationForms.caller());
            return OperationForms.accepted("Variable change requested");
        } catch (RuntimeException e) {
            return OperationForms.refused(e);
        }
    }

    @Override
    public String toString() {
        return "Set variables";
    }
}
