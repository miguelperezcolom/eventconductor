package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.*;
import io.mateu.workflow.infra.in.ui.pages.process.Processes;
import io.mateu.workflow.infra.in.ui.pages.WorkflowDefinitions;

@UI("")
@KeycloakSecured(url = "https://lemur-11.cloud-iam.com/auth", realm = "mateu", clientId = "demo")
@FavIcon("/images/riu.svg")
@PageTitle("Workflow")
@Logo("/images/riu.svg")
@Title("Workflow")
public class WorkflowHome {

    @Menu
    WorkflowDefinitions definitions;

    @Menu
    Processes processes;

}
