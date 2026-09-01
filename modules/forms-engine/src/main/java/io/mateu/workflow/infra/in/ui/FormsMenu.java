package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.infra.in.ui.pages.FormExecutions;
import io.mateu.workflow.infra.in.ui.pages.Forms;
import io.mateu.workflow.infra.in.ui.pages.Tasks;
import io.mateu.workflow.infra.in.ui.pages.TasksV2;

/**
 * Everything the forms engine's UI offers, in one menu: the navigation contract an embedding
 * application mounts to get the whole thing.
 *
 * <p>Left whole after the operations / administration split, for the same reason as
 * {@code WorkflowMenu}: narrowing it would take a section away from every embedder to serve one
 * deployment's layout. The halves are offered beside it as {@link FormsOperationsMenu} and
 * {@link FormsAdminMenu}.
 */
public class FormsMenu {

    @Menu
    Forms forms;

    @Menu
    FormExecutions executions;

    @Menu
    Tasks tasks;

    @Menu
    TasksV2 tasksV2;
}
