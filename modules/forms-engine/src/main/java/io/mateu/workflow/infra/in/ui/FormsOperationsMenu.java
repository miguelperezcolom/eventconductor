package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.infra.in.ui.pages.FormExecutions;
import io.mateu.workflow.infra.in.ui.pages.Tasks;

/**
 * The half of the forms UI that is about work waiting to be done: the executions a process created
 * and the tasks a person answers.
 *
 * <p>Split out of {@link FormsMenu} so a deployment with two consoles can keep answering tasks
 * where the work happens and move designing forms — {@link FormsAdminMenu} — behind an
 * administrator's gate. {@code FormsMenu} is unchanged and still offers all four sections, for the
 * embedders that already mount it.
 *
 * <p>The field names match {@code FormsMenu}'s, because Mateu builds a menu entry's route from the
 * field name: {@code /forms/tasks} is still {@code /forms/tasks} after the split.
 */
public class FormsOperationsMenu {

    @Menu
    FormExecutions executions;

    @Menu
    Tasks tasks;

    // Tasks v 2 is deliberately not here: two task lists side by side in a product console is a
    // question the person using it cannot answer. FormsMenu still carries it for embedders, and
    // the route still resolves.
}
