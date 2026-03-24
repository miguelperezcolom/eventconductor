package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.infra.in.ui.pages.WorkflowDefinitions;
import io.mateu.workflow.infra.in.ui.pages.process.Processes;

public class WorkflowMenu {

    @Menu
    WorkflowDefinitions definitions;

    @Menu
    Processes processes;

}
