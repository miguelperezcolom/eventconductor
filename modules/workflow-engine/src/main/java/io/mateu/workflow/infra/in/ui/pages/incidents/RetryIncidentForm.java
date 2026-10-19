package io.mateu.workflow.infra.in.ui.pages.incidents;

import io.mateu.uidl.annotations.Button;
import io.mateu.uidl.annotations.FormLayout;
import io.mateu.uidl.annotations.Help;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Stereotype;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.uidl.data.Message;
import io.mateu.uidl.data.UICommand;
import io.mateu.workflow.application.services.IncidentService;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** The "Retry all" dialog of an incident: why — and then every failed step of it is retried. */
@FormLayout(columns = 1)
public class RetryIncidentForm {

    @Hidden
    String key;

    @NotEmpty
    @Label("Reason")
    @Help("Mandatory: recorded with your name in each process's audit trail")
    @Stereotype(FieldStereotype.textarea)
    String reason;

    public RetryIncidentForm forIncident(String key) {
        this.key = key;
        return this;
    }

    @Button
    @Label("Retry all")
    public Object apply() {
        try {
            var outcome = incidentService().retryGroup(key, reason, io.mateu.workflow.infra.in.ui.pages.process.operate.OperationForms.caller());
            return List.of(Message.success("Batch operation " + outcome.batchOperationId() + " retries the "
                            + outcome.requested() + " failed step" + (outcome.requested() == 1 ? "" : "s")
                            + " of this incident in the background — follow it on the Batch operations page."),
                    UICommand.closeModal());
        } catch (RuntimeException e) {
            return Message.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static IncidentService incidentService() {
        return io.mateu.workflow.infra.in.ui.pages.process.operate.OperationForms.bean(IncidentService.class);
    }

    @Override
    public String toString() {
        return "Retry all";
    }
}
