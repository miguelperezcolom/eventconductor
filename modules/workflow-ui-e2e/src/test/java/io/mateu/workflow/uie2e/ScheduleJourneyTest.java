package io.mateu.workflow.uie2e;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import io.mateu.workflow.uie2e.support.AbstractUiE2eTest;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * UI-SCHEDULES — a schedule created, operated and promoted from the browser.
 *
 * <p>The journey an operator takes to have a workflow run on its own: Workflow → Schedules → New, an
 * id, a workflow, a cron, Create — and the schedule's page shows when it will start. From there it is
 * paused, started once by hand, and its {@code cron.yaml} entry is ready to commit.
 */
class ScheduleJourneyTest extends AbstractUiE2eTest {

    @Test
    void a_schedule_is_created_operated_and_ready_to_promote_from_the_browser() {
        ui.openWorkflowMenu();
        page.getByRole(AriaRole.MENUITEM, new Page.GetByRoleOptions().setName("Schedules")).click();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New")).first().click();

        page.getByLabel("Id").first().fill("ui-nightly");
        var workflow = page.getByLabel("Workflow").first();
        workflow.click();
        workflow.fill("UI Greeting");
        page.getByRole(AriaRole.OPTION, new Page.GetByRoleOptions().setName("UI Greeting")).first().click();
        page.getByLabel("Cron").first().fill("0 0 9 * * *");
        page.getByLabel("Zone").first().fill("UTC");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save")).first().click();

        // Create lands on the schedule's page, with its next starts.
        assertThat(page.getByText("Schedule ui-nightly").first()).isVisible();
        assertThat(page.getByLabel("When").first()).containsText("Every day at 09:00");
        assertThat(page.getByLabel("Source").first()).containsText("UI/API");
        assertThat(page.locator("li").filter(new com.microsoft.playwright.Locator.FilterOptions()
                .setHasText("09:00:00 +00:00 (UTC)")).first()).isVisible();

        // Created here, so editable: the id stays, the cron changes.
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Edit")).first().click();
        // The textbox, not the label: the view it came from shows Cron as read-only text until the form replaces it.
        page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Cron")).first().fill("0 30 9 * * *");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save")).first().click();
        assertThat(page.getByLabel("When").first()).containsText("Every day at 09:30");

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Pause")).first().click();
        assertThat(page.getByText("Paused").first()).isVisible();
        assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Resume")).first()).isVisible();

        // Run now asks first, then starts one process under a manual key — paused or not.
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Run now")).first().click();
        // The confirmation's own button — a plain <button>, where the toolbar's is a vaadin-button.
        page.locator("button").filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("Run now")).last().click();
        awaitUntil(java.time.Duration.ofSeconds(15), () -> processes.findAll().stream()
                .anyMatch(p -> p.getBusinessKey() != null
                        && p.getBusinessKey().startsWith("ui-greeting-cron-manual-ui-nightly-")));

        assertThat(page.locator("pre").filter(new com.microsoft.playwright.Locator.FilterOptions()
                .setHasText("- id: ui-nightly")).first()).isVisible();

        // The definition's Schedules tab lists it too, its id a way to this page.
        page.navigate("http://localhost:" + port + "/workflow/definitions/ui-greeting");
        page.getByRole(AriaRole.TAB, new Page.GetByRoleOptions().setName("Schedules")).click();
        page.locator("a").filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("ui-nightly")).first().click();
        assertThat(page.getByText("Schedule ui-nightly").first()).isVisible();
    }

    @Test
    void the_schedules_page_is_a_table_an_operator_scans() {
        ui.openWorkflowMenu();
        page.getByRole(AriaRole.MENUITEM, new Page.GetByRoleOptions().setName("Schedules")).click();

        assertThat(page.locator("vaadin-grid").first()).isVisible();
        for (var heading : java.util.List.of("Id", "Workflow", "Cron", "Next start", "Source", "Status")) {
            assertThat(page.locator("vaadin-grid-sorter, vaadin-grid-cell-content")
                    .filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText(heading)).first()).isVisible();
        }
    }
}
