package io.mateu.workflow.uie2e;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.uie2e.support.AbstractUiE2eTest;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * UI-SEARCH-BATCH — the Processes page narrowed by a search attribute, and a batch operation created
 * from the Batch operations page that cancels exactly the processes that attribute matches.
 */
class SearchAndBatchJourneyTest extends AbstractUiE2eTest {

    @Test
    void the_processes_of_one_customer_are_found_and_cancelled_in_one_batch() {
        var first = startProcess("ui-long-running", "batch-1", new Variable("customerId", "C-9"));
        var second = startProcess("ui-long-running", "batch-2", new Variable("customerId", "C-9"));
        var other = startProcess("ui-long-running", "batch-3", new Variable("customerId", "C-7"));

        // The listing, narrowed by the attribute (a filter restored from the address, as a bookmark would).
        page.navigate("http://localhost:" + port + "/workflow/processes?attributes="
                + URLEncoder.encode("customerId = C-9", StandardCharsets.UTF_8).replace("+", "%20"));
        assertThat(page.getByText(java.util.regex.Pattern.compile("^2 items$")).first()).isVisible();

        // Workflow → Batch operations → New: cancel the processes of customer C-9.
        page.navigate("http://localhost:" + port + "/");
        ui.awaitLoaded();
        ui.openWorkflowMenu();
        page.getByRole(AriaRole.MENUITEM, new Page.GetByRoleOptions().setName("Batch operations")).click();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New")).first().click();
        var operation = page.getByLabel("Operation").first();
        operation.click();
        page.getByRole(AriaRole.OPTION, new Page.GetByRoleOptions().setName("CANCEL")).first().click();
        page.getByLabel("Search attributes").first().fill("customerId = C-9");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save")).first().click();

        // It lands on the batch, which the runner walks in the background.
        assertThat(page.getByText(java.util.regex.Pattern.compile("^Batch operation ")).first()).isVisible();
        awaitUntil(java.time.Duration.ofSeconds(30), () ->
                processes.findById(first).orElseThrow().getStatus() == ProcessStatus.CANCELLED
                        && processes.findById(second).orElseThrow().getStatus() == ProcessStatus.CANCELLED);
        org.assertj.core.api.Assertions.assertThat(processes.findById(other).orElseThrow().getStatus())
                .isEqualTo(ProcessStatus.RUNNING);

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Refresh")).first().click();
        assertThat(page.getByLabel("Succeeded").first()).hasValue("2");
    }
}
