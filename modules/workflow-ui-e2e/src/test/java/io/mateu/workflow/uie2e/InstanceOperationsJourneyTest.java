package io.mateu.workflow.uie2e;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.uie2e.pages.ProcessDetailPage;
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
        // No operation yet, no Operations tab.
        assertThat(detail.tab("Operations")).hasCount(0);
        var freshHeader = settledHeader(detail);
        detail.runAction("Set variables");

        var dialog = page.getByRole(AriaRole.DIALOG).first();
        assertThat(dialog).isVisible();
        dialog.getByLabel("Variables to remove").first().fill("note");
        dialog.getByLabel("Reason").first().fill("The note no longer applies");
        dialog.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Apply")).click();

        awaitUntil(Duration.ofSeconds(20), () -> processes.findById(id).orElseThrow().getVariables().stream()
                .noneMatch(v -> "note".equals(v.name())));
        // The tab comes with the first operation, without a reload: the refresh after the dialog used
        // to repaint the values only, and the tab bar kept the tabs of the render before.
        assertThat(detail.tab("Operations")).isVisible();
        // That repaint redraws the page whole (the set of tabs changed), and it is the page a fresh
        // load draws: the back arrow by the title, the same buttons in the same places. It once
        // answered with the bare view model — no back arrow, Cancel process moved to the left.
        assertThat(detail.backControl()).isVisible();
        org.assertj.core.api.Assertions.assertThat(settledHeader(detail)).isEqualTo(freshHeader);
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
        // The toolbar follows: a completed process offers no step to complete. The refresh after the
        // dialog used to repaint the values only, leaving the buttons of the failed process on screen.
        assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Complete step"))).hasCount(0);
        org.assertj.core.api.Assertions.assertThat(detail.offers("Retry from failure")).isFalse();
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

            // A 1280×720 layout (a 1920×1080 screen at 150 %): the reason and both buttons are on screen
            // without scrolling — the mapping scrolls in its own region — and so is the dry run's result.
            page.setViewportSize(1280, 720);
            var preview = dialog.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions()
                    .setName("Preview"));
            var migrate = dialog.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions()
                    .setName("Migrate").setExact(true));
            assertThat(dialog.getByLabel("Reason").first()).isInViewport();
            assertThat(preview).isInViewport();
            assertThat(migrate).isInViewport();

            dialog.getByLabel("Reason").first().fill("v2 renames the wait");
            preview.click();
            assertThat(dialog.getByText(java.util.regex.Pattern.compile("can be migrated")).first()).isInViewport();
            assertThat(migrate).isInViewport();
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

    /**
     * The header's buttons once the toolbar has laid itself out: the back control and at least one
     * action, and two reads in a row that agree (the toolbar moves what does not fit behind its "⋯"
     * after it renders).
     */
    private java.util.List<String> settledHeader(ProcessDetailPage detail) {
        var last = new java.util.concurrent.atomic.AtomicReference<java.util.List<String>>(java.util.List.of());
        awaitUntil(Duration.ofSeconds(10), () -> {
            var now = detail.headerButtons();
            var settled = now.size() > 1 && now.equals(last.get());
            last.set(now);
            return settled;
        });
        return last.get();
    }

    @org.springframework.beans.factory.annotation.Autowired
    io.mateu.workflow.application.out.WorkflowDefinitionRepository definitions;

    @org.springframework.beans.factory.annotation.Autowired
    io.mateu.workflow.application.out.LogMessageRepository logs;

    @org.springframework.beans.factory.annotation.Autowired
    io.mateu.workflow.application.services.IncidentService incidents;
}
