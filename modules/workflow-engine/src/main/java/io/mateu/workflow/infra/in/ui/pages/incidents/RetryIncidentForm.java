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
import io.mateu.workflow.security.CallerResolver;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

/** The "Retry all" dialog of an incident: why — and then every failed step of it is retried. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
@FormLayout(columns = 1)
public class RetryIncidentForm {

    final IncidentService incidentService;
    final ObjectProvider<CallerResolver> callerResolver;

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
            var resolver = callerResolver == null ? null : callerResolver.getIfAvailable();
            var outcome = incidentService.retryGroup(key, reason, resolver == null ? null : resolver.current());
            return List.of(Message.success(outcome.requested() + " step retr" + (outcome.requested() == 1 ? "y" : "ies")
                            + " requested" + (outcome.async() ? " in the background" : "")
                            + (outcome.truncated() ? " — the incident held more; retry it again for the rest" : "") + "."),
                    UICommand.closeModal());
        } catch (RuntimeException e) {
            return Message.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    @Override
    public String toString() {
        return "Retry all";
    }
}
