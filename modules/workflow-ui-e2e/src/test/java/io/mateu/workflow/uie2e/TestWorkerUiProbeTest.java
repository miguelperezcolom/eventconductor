package io.mateu.workflow.uie2e;

import com.microsoft.playwright.options.AriaRole;
import io.mateu.testworker.application.ReceivedTaskStore;
import io.mateu.testworker.domain.Outcome;
import io.mateu.testworker.domain.ReceivedTask;
import io.mateu.testworker.domain.ScenarioSource;
import io.mateu.workflow.uie2e.support.AbstractUiE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

/** Temporary probe: prints what the shell actually renders, so the selectors are not guesses. */
class TestWorkerUiProbeTest extends AbstractUiE2eTest {

    @Autowired
    ReceivedTaskStore receivedTasks;

    @Test
    void whatIsOnTheWorkerPages() {
        receivedTasks.save(new ReceivedTask("exec-probe", "process-probe", "booking",
                "charge-card", "", LocalDateTime.now(), 1, ScenarioSource.TEST_CONFIG,
                "charge-card", Outcome.ERROR, 250L, LocalDateTime.now(), "card declined",
                List.of(), "{}"));

        page.getByRole(AriaRole.MENUITEM,
                new com.microsoft.playwright.Page.GetByRoleOptions().setName("Test worker")).click();
        page.getByRole(AriaRole.MENUITEM,
                new com.microsoft.playwright.Page.GetByRoleOptions().setName("Task overrides")).click();
        page.waitForTimeout(2500);
        System.out.println("### OVERRIDES BUTTONS: " + page.getByRole(AriaRole.BUTTON).allInnerTexts());
        // Try to open the create form.
        page.getByRole(AriaRole.BUTTON,
                new com.microsoft.playwright.Page.GetByRoleOptions().setName("New")).first().click();
        page.waitForTimeout(2000);
        System.out.println("### AFTER ADD, BUTTONS: " + page.getByRole(AriaRole.BUTTON).allInnerTexts());
        System.out.println("### TEXTBOXES: " + page.getByRole(AriaRole.TEXTBOX).count());
    }
}
