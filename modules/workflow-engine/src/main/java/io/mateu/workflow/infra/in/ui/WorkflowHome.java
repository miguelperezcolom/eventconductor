package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.UI;
import io.mateu.workflow.infra.in.ui.pages.Processes;
import io.mateu.workflow.infra.in.ui.pages.workflowdefinition.WorkflowDefinitions;

@UI("/workflow")
public class WorkflowHome {

    @Menu
    WorkflowDefinitions definitions;

    @Menu
    Processes processes;

}
