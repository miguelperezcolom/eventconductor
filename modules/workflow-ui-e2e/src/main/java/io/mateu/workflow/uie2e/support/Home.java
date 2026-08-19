package io.mateu.workflow.uie2e.support;

import io.mateu.testworker.infra.in.ui.TestWorkerMenu;
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

    /**
     * The test worker's pages, mounted beside the engine's rather than in a module of their own.
     * One application, one browser, one Playwright download — and the two UIs an operator of a
     * test environment actually moves between are then reachable from the same shell, which is
     * also how the standalone apps deploy them.
     */
    @Menu
    TestWorkerMenu testWorker;
}
