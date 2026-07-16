package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.infra.in.ui.pages.FormExecutions;
import io.mateu.workflow.infra.in.ui.pages.Forms;
import io.mateu.workflow.infra.in.ui.pages.Tasks;
import io.mateu.workflow.infra.in.ui.pages.TasksV2;

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
