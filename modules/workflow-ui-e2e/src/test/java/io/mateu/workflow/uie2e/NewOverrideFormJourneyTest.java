package io.mateu.workflow.uie2e;

import com.microsoft.playwright.options.AriaRole;
import io.mateu.workflow.uie2e.pages.TestWorkerUi;
import io.mateu.workflow.uie2e.support.AbstractUiE2eTest;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * UI-OVERRIDE-NEW — creating a canned reply in the browser.
 *
 * <p>Half of what the test worker's UI is for. A process you cannot edit gets its scenario from an
 * override, and an override is created here; the guide sends people to this page by name.
 */
class NewOverrideFormJourneyTest extends AbstractUiE2eTest {

    /**
     * Short on purpose, and kept short now that the form opens.
     *
     * <p>This started as the reproduction of a hang: Mateu rendered the record's three nullable
     * numeric fields as {@code vaadin-integer-field}, a null arrived as {@code NaN}, the field
     * cleared itself, the clear counted as a change, and it was set again — the page never became
     * usable. Fixed in Mateu 3.0-alpha.294, which is what enabled this test.
     *
     * <p>A short timeout is the guard against it coming back: the form either appears in a couple of
     * seconds or it is not coming, and a generous timeout would turn its return into a slow suite
     * rather than a failing test.
     */
    private static final double A_FORM_SHOULD_APPEAR_BY_NOW = 8_000;

    @Test
    void a_canned_reply_can_be_created_in_the_browser() {
        var worker = new TestWorkerUi(page).goToTaskOverrides();

        worker.button("New").first().click();

        // The form, not the route: the URL resolves either way, which is what makes the hang look
        // like slowness rather than a defect.
        assertThat(page.getByRole(AriaRole.BUTTON, new com.microsoft.playwright.Page.GetByRoleOptions()
                .setName("Save")))
                .isVisible(new com.microsoft.playwright.assertions.LocatorAssertions.IsVisibleOptions()
                        .setTimeout(A_FORM_SHOULD_APPEAR_BY_NOW));
    }
}
