package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.*;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.workflow.infra.in.ui.pages.FormExecutions;
import io.mateu.workflow.infra.in.ui.pages.Forms;

@UI("/_forms")
@KeycloakSecured(url = "https://lemur-11.cloud-iam.com/auth", realm = "mateu", clientId = "demo")
@FavIcon("/images/riu.svg")
@PageTitle("Forms")
@Logo("/images/riu.svg")
@Title("Forms")
public class FormsHome {

    @Menu
    FormsMenu forms;

    @Stereotype(FieldStereotype.html)
    String message = "<p>Welcome to the forms engine.</p>" +
            "<p>Here you will be able to create form definitions and form executions.</p>";

}
