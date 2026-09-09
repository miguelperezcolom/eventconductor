package io.mateu.workflow.uie2e;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import io.mateu.workflow.uie2e.pages.ProcessDetailPage;
import io.mateu.workflow.uie2e.support.AbstractUiE2eTest;
import org.junit.jupiter.api.Test;

/**
 * UI-PROCESS-NEW — starting a process from the browser.
 *
 * <p>The one Crud in the shell that actually offers creation: {@code WorkflowDefinitions} and the
 * forms module's {@code Tasks} both turn it off, so the "New process" form and its Create button
 * were never exercised by a browser. This is the journey an operator takes to kick off a run by
 * hand — pick a definition, give it a business key, press Create — and it must land on the new
 * process's detail rather than an error.
 */
class CreateProcessJourneyTest extends AbstractUiE2eTest {

    @Test
    void a_process_can_be_started_from_the_browser() {
        var businessKey = "ui-created-1";

        ui.goToProcesses();

        // The Crud's own "New" opens the CreateProcessForm.
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New")).first().click();

        // Definition is a lookup: open it, type the name, pick the option the engine seeded.
        var definition = page.getByLabel("Workflow Definition");
        definition.click();
        definition.fill("UI Greeting");
        page.getByRole(AriaRole.OPTION, new Page.GetByRoleOptions().setName("UI Greeting")).first().click();

        page.getByLabel("Business key").fill(businessKey);

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Create")).first().click();

        // Create returns the new id and the Crud navigates to its detail. The regression this
        // guards: Create used to reach a create()/save() that only threw UnsupportedOperationException,
        // so the detail never arrived and the operator got an error instead of a process.
        new ProcessDetailPage(page).awaitLoaded();

        // And the process really is there, under the key that was typed.
        awaitUntil(java.time.Duration.ofSeconds(10),
                () -> processes.findByBusinessKey(businessKey).isPresent());
    }
}
