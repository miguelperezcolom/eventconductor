package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.infra.in.ui.pages.WorkflowDefinitions;
import io.mateu.workflow.infra.in.ui.pages.analytics.Analytics;
import io.mateu.workflow.infra.in.ui.pages.process.Processes;
import io.mateu.workflow.infra.in.ui.pages.steps.StepExecutions;

/**
 * Everything the engine's UI offers, in one menu: the navigation contract an embedding application
 * mounts to get the whole thing.
 *
 * <p>Deliberately left whole after the operations / administration split. An application that
 * mounts this keeps getting all four sections, which is what the testbenches and the UI e2e suite
 * do and what any embedder outside this repository has already been given. The split is offered
 * <em>beside</em> it — {@link WorkflowOperationsMenu} and {@link WorkflowAdminMenu} — for a
 * deployment that puts the two halves on different consoles behind different gates. Narrowing this
 * class instead would have silently taken two sections away from every embedder to serve one
 * deployment's layout.
 */
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
