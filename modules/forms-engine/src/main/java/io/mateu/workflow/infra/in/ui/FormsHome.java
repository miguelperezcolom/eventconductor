package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.*;
import io.mateu.workflow.infra.in.ui.pages.FormExecutions;
import io.mateu.workflow.infra.in.ui.pages.Forms;

@UI("/forms")
@KeycloakSecured(url = "https://lemur-11.cloud-iam.com/auth", realm = "mateu", clientId = "demo")
@FavIcon("/images/riu.svg")
@PageTitle("Froms")
@Logo("/images/riu.svg")
@Title("Forms")
public class FormsHome {

    @Menu
    Forms forms;

    @Menu
    FormExecutions executions;

    @Html
    String message = "<p>Welcome to the forms engine.</p>" +
            "<p>Here you will be able to create form definitions and form executions.</p>";

}
