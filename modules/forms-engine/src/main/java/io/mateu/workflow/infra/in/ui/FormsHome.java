package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.KeycloakSecured;
import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.UI;
import io.mateu.workflow.infra.in.ui.pages.FormExecutions;
import io.mateu.workflow.infra.in.ui.pages.Forms;

@UI("/forms")
@KeycloakSecured(url = "https://lemur-11.cloud-iam.com/auth", realm = "mateu", clientId = "demo")
public class FormsHome {

    @Menu
    Forms forms;

    @Menu
    FormExecutions executions;
}
