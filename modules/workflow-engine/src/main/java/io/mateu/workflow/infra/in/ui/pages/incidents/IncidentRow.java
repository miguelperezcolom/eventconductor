package io.mateu.workflow.infra.in.ui.pages.incidents;

import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.interfaces.Identifiable;
import io.mateu.workflow.application.services.IncidentService.IncidentGroup;
import io.mateu.workflow.infra.in.ui.pages.process.childcruds.Step;

/** One row of the Incidents page: a step of a workflow failing with one family of error. */
public record IncidentRow(
        @Label("Key") String id,
        @Label("Workflow") String workflow,
        @Label("Step") String step,
        @Label("Error") String error,
        @Label("Failed steps") int failedSteps,
        @Label("Processes") int processes,
        @Label("First seen") String firstSeen,
        @Label("Last seen") String lastSeen
) implements Identifiable {

    public static IncidentRow of(IncidentGroup group) {
        return new IncidentRow(group.key(), group.workflowDefinitionId(), group.stepId(), group.errorFamily(),
                group.failedSteps(), group.processes(), Step.format(group.firstSeen()), Step.format(group.lastSeen()));
    }
}
