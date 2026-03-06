package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.MateuUI;
import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.infra.in.ui.pages.Forms;
import io.mateu.workflow.infra.in.ui.pages.Processes;
import io.mateu.workflow.infra.in.ui.pages.WorkflowDefinitions;

@MateuUI("/workflow")
public class WorkflowHome {

    @Menu
    Forms forms;

    @Menu
    WorkflowDefinitions definitions;

    @Menu
    Processes processes;

}
