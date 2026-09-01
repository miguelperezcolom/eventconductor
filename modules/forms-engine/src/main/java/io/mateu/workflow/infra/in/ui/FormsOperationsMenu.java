package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.infra.in.ui.pages.FormExecutions;
import io.mateu.workflow.infra.in.ui.pages.Tasks;
import io.mateu.workflow.infra.in.ui.pages.TasksV2;

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

    @Menu
    TasksV2 tasksV2;
}
