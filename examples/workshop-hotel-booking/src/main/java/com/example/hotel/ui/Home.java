package com.example.hotel.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.UI;
import io.mateu.workflow.infra.in.ui.WorkflowMenu;

/** The console: the engine's Workflow menu. */
@UI("")
public class Home {

    @Menu
    WorkflowMenu workflow;
}
