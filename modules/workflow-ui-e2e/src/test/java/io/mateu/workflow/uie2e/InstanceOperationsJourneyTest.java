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

    @Test
    void anOperatorCompletesAFailedStepByHand() {
        var id = startProcess("ui-failing", "operate-2");
        awaitUntil(Duration.ofSeconds(20), () -> processes.findById(id).orElseThrow().getStatus() == ProcessStatus.ERROR);

        var detail = ui.goToProcesses().open(id);
        detail.runAction("Complete step");

        var dialog = page.getByRole(AriaRole.DIALOG).first();
        assertThat(dialog).isVisible();
        dialog.getByLabel("Reason").first().fill("Paid by phone");
        dialog.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Complete step")).click();

        awaitUntil(Duration.ofSeconds(20), () -> processes.findById(id).orElseThrow().getStatus() == ProcessStatus.COMPLETED);
        assertThat(detail.statusBadge()).containsText("Completed");
    }

    @Test
    void anIncidentIsOpenedAndRetriedAsAWhole() {
        var id = startProcess("ui-failing", "incident-retry-1");
        awaitUntil(Duration.ofSeconds(20), () -> processes.findById(id).orElseThrow().getStatus() == ProcessStatus.ERROR);

        ui.goToIncidents();
        assertThat(page.getByText("The card was declined").first()).isVisible();
        // The key is the row's link into the incident.
        var key = incidents.incidents("ui-failing").groups().getFirst().key();
        page.getByText(key).first().click();
        assertThat(page.getByText(id).first()).isVisible();

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Retry all")).first().click();
        var dialog = page.getByRole(AriaRole.DIALOG).first();
        assertThat(dialog).isVisible();
        dialog.getByLabel("Reason").first().fill("Gateway back up");
        dialog.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Retry all")).click();

        awaitUntil(Duration.ofSeconds(20), () -> logs.findByProcessId(id).stream()
                .anyMatch(line -> line.getMessage().contains("[retry-incident]")
                        && line.getMessage().contains("Gateway back up")));

        // The drill-down: each process of the incident links to its detail.
        page.getByText(id).first().click();
        new io.mateu.workflow.uie2e.pages.ProcessDetailPage(page).awaitLoaded();
    }

    @Test
    void anOperatorPreviewsAndMigratesAProcessToTheNewVersion() {
        var id = startProcess("ui-long-running", "migrate-1");
        var original = definitions.findById("ui-long-running").orElseThrow();
        try {
            var renamed = original.steps().stream()
                    .map(step -> "wait".equals(step.id()) ? step.withName("Wait a long while, v2") : step).toList();
            definitions.save(original.withSteps(renamed).withVersion(original.version() + 1));

            var detail = ui.goToProcesses().open(id);
            detail.runAction("Migrate…");
            var dialog = page.getByRole(AriaRole.DIALOG).first();
            assertThat(dialog).isVisible();
            assertThat(dialog.getByLabel("Step mapping").first()).hasValue(java.util.regex.Pattern.compile("wait=wait"));
            dialog.getByLabel("Reason").first().fill("v2 renames the wait");
            dialog.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Preview")).click();
            assertThat(page.getByText(java.util.regex.Pattern.compile("can be migrated")).first()).isVisible();
            dialog.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Migrate")
                    .setExact(true)).click();

            awaitUntil(Duration.ofSeconds(20), () ->
                    processes.findById(id).orElseThrow().getWorkflowDefinitionVersion() == original.version() + 1);
            page.reload();
            detail.openTab("Operations");
            assertThat(page.getByText(java.util.regex.Pattern.compile("\\[migrate\\] by .*migrated from v")).first()).isVisible();
        } finally {
            definitions.save(original);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    io.mateu.workflow.application.out.WorkflowDefinitionRepository definitions;

    @org.springframework.beans.factory.annotation.Autowired
    io.mateu.workflow.application.out.LogMessageRepository logs;

    @org.springframework.beans.factory.annotation.Autowired
    io.mateu.workflow.application.services.IncidentService incidents;
}
