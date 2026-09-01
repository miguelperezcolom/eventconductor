package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.FavIcon;
import io.mateu.uidl.annotations.Logo;
import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.PageTitle;
import io.mateu.uidl.annotations.Stereotype;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.UI;
import io.mateu.uidl.data.FieldStereotype;

/**
 * The forms engine's administration UI, on a base URL of its own so it can be mounted somewhere
 * else than {@link FormsHome}.
 *
 * <p>Same pod, same pages, second front door — see {@link WorkflowAdminHome} for the reasoning,
 * which is the same one. Here it separates designing a form from answering one, which are done by
 * different people even in a deployment that has only one console.
 *
 * <p>No {@code @KeycloakSecured}: {@code FormsHome} carries one naming a host that is not this
 * deployment's, and copying it would bake a second wrong issuer into a second bootstrap page. The
 * engine authenticates nothing of its own in either case; whatever sits in front of it does.
 */
@UI("/_forms-admin")
@FavIcon("/images/riu.svg")
@PageTitle("Forms administration")
@Logo("/images/riu.svg")
@Title("Forms administration")
public class FormsAdminHome {

    // Named "forms" so the route stays /forms/forms — see WorkflowAdminHome's note.
    @Menu
    FormsAdminMenu forms;

    @Stereotype(FieldStereotype.html)
    String message = "<p>Administration of the forms engine.</p>" +
            "<p>The form definitions this engine imported.</p>";

}
