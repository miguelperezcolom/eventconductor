package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.infra.in.ui.pages.Forms;

/**
 * The half of the forms UI that is about designing forms rather than filling them in.
 *
 * <p>A form definition is configuration — the same kind of thing as a workflow definition — so it
 * belongs wherever a deployment keeps configuration, which is not necessarily where its users
 * answer their tasks. See {@link FormsOperationsMenu} for the other half.
 */
public class FormsAdminMenu {

    @Menu
    Forms forms;
}
