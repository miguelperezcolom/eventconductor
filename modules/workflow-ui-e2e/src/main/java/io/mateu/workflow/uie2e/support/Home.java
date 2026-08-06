package io.mateu.workflow.uie2e.support;

import io.mateu.uidl.annotations.Menu;
import io.mateu.uidl.annotations.UI;
import io.mateu.workflow.infra.in.ui.WorkflowMenu;

/**
 * The whole UI under test: the engine contributes its own menu and the tests navigate from here.
 *
 * <p>A top-level class rather than a nested one, the same as the testbench app — Mateu resolves
 * {@code @UI} by scanning for top-level types, and a nested one is simply never mounted, which
 * shows up as a blank page rather than as an error.
 */
@UI("")
public class Home {

    @Menu
    WorkflowMenu workflow;
}
