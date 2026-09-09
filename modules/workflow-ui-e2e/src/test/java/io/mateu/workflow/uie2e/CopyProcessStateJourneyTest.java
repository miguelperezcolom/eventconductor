package io.mateu.workflow.uie2e;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.uie2e.support.AbstractUiE2eTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UI-PROCESS-COPY — copying a process's state to the clipboard from its detail.
 *
 * <p>The operator's "give me this to paste into a ticket" button. It exercises the whole path a
 * server-side action cannot take on its own: the toolbar action assembles the JSON, hands it to the
 * page as an event, and the frontend clipboard bridge writes it and shows a toast. So it asserts
 * both halves — the toast the operator sees, and the JSON that actually lands on the clipboard.
 */
class CopyProcessStateJourneyTest extends AbstractUiE2eTest {

    @Test
    void the_process_state_can_be_copied_to_the_clipboard() {
        // clipboard.writeText/readText need the permission granted to the context.
        page.context().grantPermissions(List.of("clipboard-read", "clipboard-write"));

        var businessKey = "ui-copy-1";
        var processId = startProcess("ui-greeting", businessKey, new Variable("who", "world"));

        ui.goToProcesses().open(processId);

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Copy state")).first().click();

        // The toast the operator reads.
        PlaywrightAssertions.assertThat(page.getByText("Process state copied to the clipboard")).isVisible();

        // And the JSON that actually reached the clipboard — its identity, and the variable it ran with.
        var clip = (String) page.evaluate("async () => await navigator.clipboard.readText()");
        assertThat(clip).contains(businessKey).contains("\"who\"").contains("world");
    }
}
