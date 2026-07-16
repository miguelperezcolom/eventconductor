package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.infra.in.ui.pages.WorkflowDefinitions;
import io.mateu.workflow.infra.in.ui.pages.analytics.Analytics;
import io.mateu.workflow.infra.in.ui.pages.process.Processes;
import io.mateu.workflow.infra.in.ui.pages.steps.StepExecutions;

public class WorkflowMenu {

    @Menu
    WorkflowDefinitions definitions;

    @Menu
    Processes processes;

    @Menu
    StepExecutions steps;

    @Menu
    Analytics analytics;

}
