package io.mateu.workflow.infra.in.ui.pages.process.operate;

import io.mateu.uidl.annotations.Button;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.Help;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Stereotype;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.data.Message;
import io.mateu.workflow.application.services.SensitiveVariableRevealService;
import jakarta.validation.constraints.NotEmpty;

/**
 * The "Reveal variable" dialog of the process detail: one sensitive variable's value, in clear, for a
 * caller holding one of {@code workflow.security.sensitive.reveal-roles} — the reveal (or its refusal) is
 * recorded with the caller's name and the reason in the process's audit trail. A plain view model, like
 * the other operation dialogs.
 */
@FormLayout(columns = 1)
public class RevealVariableForm {

    @Hidden
    String processId;

    @NotEmpty
    @Label("Variable")
    @Help("The name of a sensitive variable of this process (shown as ••••)")
    String name;

    @NotEmpty
    @Label("Reason")
    @Help("Mandatory: recorded with your name in the process's audit trail")
    @Stereotype(FieldStereotype.textarea)
    String reason;

    public RevealVariableForm forProcess(String processId) {
        this.processId = processId;
        return this;
    }

    @Button
    @Label("Reveal")
    public Object reveal() {
        try {
            var value = OperationForms.bean(SensitiveVariableRevealService.class)
                    .reveal(processId, name, reason, OperationForms.caller());
            return Message.success(name + " = " + value);
        } catch (RuntimeException e) {
            return OperationForms.refused(e);
        }
    }

    @Override
    public String toString() {
        return "Reveal variable";
    }
}
