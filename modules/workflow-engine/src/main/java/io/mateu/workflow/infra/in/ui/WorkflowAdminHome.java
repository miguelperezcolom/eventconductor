package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.FavIcon;
import io.mateu.uidl.annotations.Logo;
import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.PageTitle;
import io.mateu.uidl.annotations.PageWidth;
import io.mateu.uidl.annotations.PageWidthStyle;
import io.mateu.uidl.annotations.Stereotype;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.UI;
import io.mateu.uidl.data.FieldStereotype;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

/**
 * The engine's administration UI, on a base URL of its own so it can be mounted somewhere else
 * than {@link WorkflowHome}.
 *
 * <p>Same pod, same pages, second front door. A deployment that runs two consoles — one for using
 * the product, one for administering the platform — mounts {@code /_workflow} in the first and
 * {@code /_workflow-admin} in the second, and its gateway can then require a different role on
 * each. A deployment that runs one console mounts whichever it wants, or {@link WorkflowMenu} for
 * all of it; nothing here forces the split on anyone.
 *
 * <p>Two base URLs rather than one menu filtered by role, because the role is not something this
 * engine knows: it runs with its own security disabled in front of a gateway that authenticates,
 * and a menu that hid entries would be hiding them from a request it cannot judge. A separate path
 * is something a gateway <em>can</em> judge, with the rule written in one place instead of two.
 *
 * <p>No dashboard on purpose. {@code WorkflowHome}'s costs two aggregates over the process table
 * on every hydration that reaches it, and an administrator arriving at Definitions has not asked
 * for a count of running processes.
 */
@UI("/_workflow-admin")
@FavIcon("/images/riu.svg")
@PageTitle("Workflow administration")
@Logo("/images/riu.svg")
@Title("")
@Style(HomeStyles.PAGE)
// Uncapped for the same reason as WorkflowHome: this is a root @UI, and the definition detail it
// leads to draws a graph that a 1408px column squeezes into unreadability.
@PageWidth(PageWidthStyle.FULL_WIDTH)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
public class WorkflowAdminHome {

    // Named "workflow", like the field in WorkflowHome and WorkflowMenu, because Mateu builds a
    // menu entry's route from the field name. Keeping it means /workflow/definitions is still
    // /workflow/definitions after moving consoles — only the base URL it hangs from changed.
    @Menu
    WorkflowAdminMenu workflow;

    @Stereotype(FieldStereotype.html)
    String message = "<p>Administration of the event driven orchestrator.</p>" +
            "<p>The definitions this engine imported, and how it has been behaving.</p>";

}
