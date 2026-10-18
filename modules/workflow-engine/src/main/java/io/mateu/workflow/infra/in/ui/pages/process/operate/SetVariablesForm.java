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
import io.mateu.workflow.security.CallerResolver;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** The "Set variables" dialog of the process detail — see {@code SetProcessVariablesUseCase}. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
@FormLayout(columns = 1)
public class SetVariablesForm {

    final ProcessOperationsService processOperations;
    final ObjectProvider<CallerResolver> callerResolver;

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
            processOperations.setVariables(processId, OperationForms.variables(set), OperationForms.names(remove), reason,
                    OperationForms.caller(callerResolver));
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
