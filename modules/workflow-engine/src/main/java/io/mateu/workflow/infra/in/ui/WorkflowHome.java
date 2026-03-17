package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.KeycloakSecured;
import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.UI;
import io.mateu.workflow.infra.in.ui.pages.Processes;
import io.mateu.workflow.infra.in.ui.pages.WorkflowDefinitions;

@UI("/workflow")
@KeycloakSecured(url = "https://lemur-11.cloud-iam.com/auth", realm = "mateu", clientId = "demo")
public class WorkflowHome {

    @Menu
    WorkflowDefinitions definitions;

    @Menu
    Processes processes;

}
