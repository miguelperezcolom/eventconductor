package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.workflow.infra.in.ui.pages.process.Processes;
import io.mateu.workflow.infra.in.ui.pages.WorkflowDefinitions;

@UI("/_workflow")
@KeycloakSecured(url = "https://lemur-11.cloud-iam.com/auth", realm = "mateu", clientId = "demo")
@FavIcon("/images/riu.svg")
@PageTitle("Workflow")
@Logo("/images/riu.svg")
@Title("Workflow")
public class WorkflowHome {

    @Stereotype(FieldStereotype.html)
    String message = "<p>Welcome to the event driven orchestrator.</p>" +
            "<p>Here you will be able to create workflow definitions and processes.</p>";

    @Menu
    WorkflowMenu workflow;

}
