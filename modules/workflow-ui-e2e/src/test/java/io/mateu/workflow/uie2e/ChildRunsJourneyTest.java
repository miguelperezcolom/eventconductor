package io.mateu.workflow.uie2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.uie2e.pages.ProcessesPage;
import io.mateu.workflow.uie2e.support.AbstractUiE2eTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * A forEach over PROCESS starts one child run per item. The operator reads them from the parent — they
 * are not listed beside it — and the parent says why one of them failed.
 */
class ChildRunsJourneyTest extends AbstractUiE2eTest {

    private String failedParent() {
        var id = startProcess("ui-children", "booking-children");
        awaitUntil(Duration.ofSeconds(20), () -> processes.findById(id).orElseThrow().getStatus() == ProcessStatus.ERROR);
        return id;
    }

    @Test
    void theListShowsTheParentAndItsBusinessKeyNotItsChildren() {
        failedParent();

        var processes = ui.goToProcesses();

        assertThat(processes.itemCount()).containsText("1 item");
        assertThat(processes.rowContaining("booking-children")).isVisible();

        // Asked for, the children are there: one per item.
        page.navigate("http://localhost:" + port + "/workflow/processes?includeChildren=true");
        var all = new ProcessesPage(page);
        all.awaitLoaded();
        assertThat(all.itemCount()).containsText("3 items");
    }

    @Test
    void theParentsErrorsTabSaysWhyAChildFailed() {
        var id = failedParent();

        var detail = ui.goToProcesses().open(id);
        detail.openTab("Errors");

        // The worker's reason is in the child's log; the parent lists it against the iteration that failed.
        assertThat(detail.tabShows("The card was declined")).isVisible();
        assertThat(detail.tabShows("rooms · Room 101 › charge")).isVisible();
    }
}
