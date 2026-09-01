package io.mateu.workflow.infra.in.ui;

import io.mateu.uidl.annotations.Menu;
import io.mateu.workflow.infra.in.ui.pages.WorkflowDefinitions;
import io.mateu.workflow.infra.in.ui.pages.analytics.Analytics;

/**
 * The half of the engine's UI that is about how the engine is configured and how it is behaving,
 * rather than about the work itself.
 *
 * <p>Definitions belong here because a definition is configuration — imported from the classpath,
 * git or the database, never authored on the page — and Analytics because it measures the engine
 * rather than the business: both are read by whoever runs the platform, not by whoever uses it.
 *
 * <p>See {@link WorkflowOperationsMenu} for the other half, and for why {@link WorkflowMenu} is
 * left offering all four.
 */
public class WorkflowAdminMenu {

    @Menu
    WorkflowDefinitions definitions;

    @Menu
    Analytics analytics;

}
