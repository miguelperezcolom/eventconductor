package io.mateu.workflow.infra.in.ui.pages.incidents;

import io.mateu.uidl.annotations.Action;
import io.mateu.uidl.annotations.Colspan;
import io.mateu.uidl.annotations.Hidden;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.ReadOnly;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.data.ButtonStyle;
import io.mateu.uidl.data.Element;
import io.mateu.uidl.di.MateuBeanProvider;
import io.mateu.workflow.application.services.IncidentService;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Step;
import io.mateu.workflow.infra.in.ui.pages.process.operate.OperationForms;
import io.mateu.workflow.infra.in.ui.pages.schedules.ScheduleFormats;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * One incident: what failed, how often and since when, its processes (each a link to the process),
 * and "Retry all", which asks for a reason. See {@link IncidentService}.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
@ReadOnly
public class IncidentDetailView {

    /** How many of the group's processes the page lists. */
    static final int LISTED = 200;

    final IncidentService incidentService;

    @Hidden
    String key;

    @Label("Workflow")
    String workflow;

    @Label("Step")
    String step;

    @Label("Error family")
    @Colspan(2)
    String errorFamily;

    @Label("Latest error")
    @Colspan(2)
    String latestError;

    @Label("Failed steps")
    int failedSteps;

    @Label("Processes")
    int processes;

    @Label("First seen")
    String firstSeen;

    @Label("Last seen")
    String lastSeen;

    @Label("")
    @Colspan(2)
    Element members;

    public IncidentDetailView load(String key) {
        this.key = key;
        var group = incidentService.incident(key);
        if (group == null) {
            this.errorFamily = "This incident is resolved: none of its processes is failed any more.";
            return this;
        }
        this.workflow = group.workflowDefinitionId();
        this.step = group.stepId();
        this.errorFamily = group.errorFamily();
        this.latestError = group.sampleError();
        this.failedSteps = group.failedSteps();
        this.processes = group.processes();
        this.firstSeen = Step.format(group.firstSeen());
        this.lastSeen = Step.format(group.lastSeen());
        var html = new StringBuilder("<h4>Processes</h4><table style=\"width:100%; border-collapse: collapse;\">"
                + "<thead><tr><th style=\"text-align:left\">Process</th><th style=\"text-align:left\">Business key</th>"
                + "<th style=\"text-align:left\">Status</th><th style=\"text-align:left\">Failed at</th>"
                + "<th style=\"text-align:left\">Error</th></tr></thead><tbody>");
        for (var member : incidentService.members(key, LISTED)) {
            html.append("<tr><td><a href=\"../processes/").append(ScheduleFormats.pathSegment(member.processId()))
                    .append("\">").append(ScheduleFormats.escape(member.processId())).append("</a></td><td>")
                    .append(ScheduleFormats.escape(member.businessKey())).append("</td><td>")
                    .append(member.status()).append("</td><td>")
                    .append(ScheduleFormats.escape(Step.format(member.failedAt()))).append("</td><td>")
                    .append(ScheduleFormats.escape(member.error())).append("</td></tr>");
        }
        html.append("</tbody></table>");
        this.members = Element.builder()
                .attributes(Map.of())
                .on(Map.of())
                .name("div")
                .html(true)
                .content(html.toString())
                .build();
        return this;
    }

    /** Retries every failed step of the incident — asks for the reason first. */
    @Toolbar(buttonStyle = ButtonStyle.primary)
    @Label("Retry all")
    @Action
    public Object retryAll() {
        return OperationForms.dialog("Retry every process of this incident",
                MateuBeanProvider.getBean(RetryIncidentForm.class).forIncident(key));
    }

    @Override
    public String toString() {
        return step != null ? "Incident " + workflow + " / " + step : "Incident";
    }
}
