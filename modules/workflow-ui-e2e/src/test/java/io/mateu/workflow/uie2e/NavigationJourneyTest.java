package io.mateu.workflow.uie2e;

import io.mateu.workflow.uie2e.support.AbstractUiE2eTest;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Getting around: the menu the engine contributes, and the list it leads to.
 *
 * <p>The menu is the engine's navigation contract with any application that embeds it — an app
 * mounts {@code WorkflowMenu} and gets these entries. Losing one is not a compile error anywhere.
 */
class NavigationJourneyTest extends AbstractUiE2eTest {

    @Test
    void theWorkflowMenuOffersTheEnginesFourSections() {
        org.assertj.core.api.Assertions.assertThat(ui.workflowMenuItems())
                .containsExactlyInAnyOrder("Definitions", "Processes", "Steps", "Analytics");
    }

    @Test
    void theProcessListShowsAProcessThatWasStarted() {
        startProcess("ui-greeting", "greeting-1");

        var processes = ui.goToProcesses();

        assertThat(processes.grid()).isVisible();
        assertThat(processes.rowContaining("UI Greeting")).isVisible();
    }

    /**
     * The columns are what makes the list usable — an operator scanning for a stuck process reads
     * status and start time, not ids.
     */
    @Test
    void theProcessListIsColumnedTheWayAnOperatorReadsIt() {
        startProcess("ui-greeting", "greeting-columns");

        var processes = ui.goToProcesses();

        for (var heading : java.util.List.of("Id", "Name", "Status", "Created", "Started", "Finished")) {
            assertThat(processes.column(heading)).isVisible();
        }
    }

    /** A completed process reaches its terminal status and the list says so, not just the database. */
    @Test
    void aProcessThatCompletesIsShownAsCompleted() {
        startProcess("ui-greeting", "greeting-completed");

        var processes = ui.goToProcesses();

        assertThat(processes.rowContaining("Completed")).isVisible();
    }

    /**
     * Asserted on the grid's own item count rather than by counting cells: {@code vaadin-grid}
     * keeps its cell elements in the light DOM and reuses them, so a filtered-out row is still
     * there — hidden, holding its old text. Counting cells passes on an unfiltered grid and fails
     * on a filtered one, which is the wrong way round.
     */
    @Test
    void searchNarrowsTheListToTheProcessLookedFor() {
        startProcess("ui-greeting", "greeting-wanted");
        startProcess("ui-saga", "saga-unwanted");

        var processes = ui.goToProcesses();
        assertThat(processes.itemCount()).containsText("2 items");

        processes.search("UI Greeting");

        assertThat(processes.itemCount()).containsText("1 item");
        assertThat(processes.rowContaining("UI Greeting")).isVisible();
    }
}
