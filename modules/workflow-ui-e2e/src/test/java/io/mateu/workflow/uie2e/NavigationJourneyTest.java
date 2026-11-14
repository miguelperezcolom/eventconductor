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
    void theWorkflowMenuOffersTheEnginesSections() {
        org.assertj.core.api.Assertions.assertThat(ui.workflowMenuItems())
                .containsExactlyInAnyOrder("Definitions", "Processes", "Steps", "Tasks", "Schedules", "Event triggers",
                        "Received events", "Incidents", "Analytics", "Batch operations");
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

        for (var heading : java.util.List.of("Id", "Name", "Business key", "Status", "Created", "Started", "Finished")) {
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
     * The console is English whatever the browser asks for: the engine's own words are, and Mateu's —
     * the "New" button, the search box — used to follow {@code Accept-Language}, which on a Spanish
     * browser gave "Nuevo" beside "Retry all matching".
     */
    @Test
    void theConsoleIsInOneLanguageWhateverTheBrowserAsks() {
        var spanish = page.context().browser().newContext(new com.microsoft.playwright.Browser.NewContextOptions()
                .setViewportSize(1920, 1200).setLocale("es-ES")
                .setExtraHTTPHeaders(java.util.Map.of("Accept-Language", "es-ES,es;q=0.9"))).newPage();
        try {
            spanish.navigate("http://localhost:" + port + "/workflow/processes");
            assertThat(spanish.getByPlaceholder("Search").first()).isVisible();
            assertThat(spanish.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new com.microsoft.playwright.Page.GetByRoleOptions().setName("New").setExact(true)).first()).isVisible();
            assertThat(spanish.getByPlaceholder("Buscar")).hasCount(0);
        } finally {
            spanish.context().close();
        }
    }

    /** The console's multi-word pages answer at the kebab-case spelling the REST API and the docs use. */
    @Test
    void aKebabCasePageNameLandsOnThePage() {
        page.navigate("http://localhost:" + port + "/workflow/batch-operations");
        assertThat(page).hasURL(java.util.regex.Pattern.compile(".*/workflow/batchOperations$"));
        assertThat(page.getByRole(com.microsoft.playwright.options.AriaRole.HEADING,
                new com.microsoft.playwright.Page.GetByRoleOptions().setName("Batch operations"))).isVisible();
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
