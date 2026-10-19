package io.mateu.workflow.uie2e;

import com.microsoft.playwright.options.AriaRole;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.uie2e.support.AbstractUiE2eTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * A sensitive variable in the console: the Variables tab shows the mask, never the card number, and the
 * Reveal action asks for a reason and refuses a caller without a reveal role — leaving the refusal in the
 * Operations tab.
 */
class SensitiveVariablesJourneyTest extends AbstractUiE2eTest {

    static final String CARD = "4111111111111111";

    @Test
    void theVariablesTabMasksTheCardAndARevealWithoutTheRoleIsRefusedAndAudited() {
        var id = startProcess("ui-sensitive", "sensitive-1", new Variable("cardNumber", CARD), new Variable("amount", "120"));

        var detail = ui.goToProcesses().open(id);
        detail.openTab("Variables");
        assertThat(detail.tabShows("••••")).isVisible();
        assertThat(detail.tabShows("120")).isVisible();
        org.assertj.core.api.Assertions.assertThat(page.content()).doesNotContain(CARD);

        detail.runAction("Reveal variable");
        var dialog = page.getByRole(AriaRole.DIALOG).first();
        assertThat(dialog).isVisible();
        dialog.getByLabel("Variable").first().fill("cardNumber");
        dialog.getByLabel("Reason").first().fill("Chargeback CB-1");
        dialog.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Reveal")).click();
        assertThat(page.getByText(java.util.regex.Pattern.compile("needs one of the roles")).first()).isVisible();
        org.assertj.core.api.Assertions.assertThat(page.content()).doesNotContain(CARD);

        awaitUntil(Duration.ofSeconds(10), () -> logs.findByProcessId(id).stream()
                .anyMatch(line -> line.getMessage().contains("[reveal-variable]") && line.getMessage().contains("NOT carried out")));
        page.reload();
        detail.openTab("Operations");
        assertThat(page.getByText(java.util.regex.Pattern.compile("\\[reveal-variable\\] by .* NOT carried out")).first())
                .isVisible();
    }

    @org.springframework.beans.factory.annotation.Autowired
    io.mateu.workflow.application.out.LogMessageRepository logs;
}
