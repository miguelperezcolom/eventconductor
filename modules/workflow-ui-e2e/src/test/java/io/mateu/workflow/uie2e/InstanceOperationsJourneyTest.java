package io.mateu.workflow.uie2e;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.uie2e.support.AbstractUiE2eTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * The operator's way into a running process: the Incidents page that groups the failures, and a
 * manual operation from the process detail — a dialog that asks for the reason, and the Operations tab
 * that shows what was done.
 */
class InstanceOperationsJourneyTest extends AbstractUiE2eTest {

    @Test
    void failuresOfOneCauseShowAsOneIncident() {
        startProcess("ui-failing", "incident-1");
        startProcess("ui-failing", "incident-2");
        awaitUntil(Duration.ofSeconds(20), () -> processes.findByBusinessKey("incident-2")
                .map(p -> p.getStatus() == ProcessStatus.ERROR).orElse(false)
                && processes.findByBusinessKey("incident-1").map(p -> p.getStatus() == ProcessStatus.ERROR).orElse(false));

        ui.goToIncidents();

        assertThat(page.getByText("The card was declined").first()).isVisible();
        assertThat(page.getByText("charge", new Page.GetByTextOptions().setExact(true)).first()).isVisible();
    }

    @Test
    void anOperatorRemovesAVariableGivingAReason() {
        var id = startProcess("ui-long-running", "operate-1", new Variable("note", "stale"));

        var detail = ui.goToProcesses().open(id);
        detail.runAction("Set variables");

        var dialog = page.getByRole(AriaRole.DIALOG).first();
        assertThat(dialog).isVisible();
        dialog.getByLabel("Variables to remove").first().fill("note");
        dialog.getByLabel("Reason").first().fill("The note no longer applies");
        dialog.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Apply")).click();

        awaitUntil(Duration.ofSeconds(20), () -> processes.findById(id).orElseThrow().getVariables().stream()
                .noneMatch(v -> "note".equals(v.name())));
        page.reload();
        detail.openTab("Operations");
        assertThat(page.getByText(java.util.regex.Pattern.compile("\\[set-variables\\] by .*removed note")).first())
                .isVisible();
    }
}
