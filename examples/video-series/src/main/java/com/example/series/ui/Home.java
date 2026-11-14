package com.example.series.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.UI;
import io.mateu.workflow.infra.in.ui.FormsMenu;
import io.mateu.workflow.infra.in.ui.WorkflowMenu;

/** The console: the engine's Workflow menu and, beside it, the forms engine's (the manager's task inbox). */
@UI("")
public class Home {

    @Menu
    WorkflowMenu workflow;

    @Menu
    FormsMenu forms;
}
