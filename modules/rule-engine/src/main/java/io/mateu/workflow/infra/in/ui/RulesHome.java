package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.FavIcon;
import io.mateu.uidl.annotations.KeycloakSecured;
import io.mateu.uidl.annotations.Logo;
import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.PageTitle;
import io.mateu.uidl.annotations.Stereotype;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.UI;
import io.mateu.uidl.data.FieldStereotype;

@UI("/_rules")
@KeycloakSecured(url = "https://lemur-11.cloud-iam.com/auth", realm = "mateu", clientId = "demo")
@FavIcon("/images/riu.svg")
@PageTitle("Rules")
@Logo("/images/riu.svg")
@Title("Rules")
public class RulesHome {

    @Menu
    RulesMenu rules;

    @Stereotype(FieldStereotype.html)
    String message = "<p>Welcome to the rule engine.</p>" +
            "<p>Here you can create and manage business rule definitions " +
            "(expression rules and decision tables).</p>";
}
