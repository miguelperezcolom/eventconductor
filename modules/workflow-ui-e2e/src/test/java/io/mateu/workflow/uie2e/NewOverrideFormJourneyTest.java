package io.mateu.workflow.uie2e;

import com.microsoft.playwright.options.AriaRole;
import io.mateu.workflow.uie2e.pages.TestWorkerUi;
import io.mateu.workflow.uie2e.support.AbstractUiE2eTest;
import org.junit.jupiter.api.Disabled;
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
     * Short on purpose. This test documents a hang, and a test that documents a hang must fail
     * quickly rather than reproduce it — the form either appears in a couple of seconds or it is
     * never coming.
     */
    private static final double A_FORM_SHOULD_APPEAR_BY_NOW = 8_000;

    @Disabled("""
            /_worker/taskOverrides/new hangs the browser, so this cannot run in CI: it would not
            fail, it would sit there.

            TaskOverride has three nullable numeric components — durationMs, failuresBeforeSuccess,
            replyTimes — and on a new record all three are null. Mateu renders them as
            <vaadin-integer-field>, the null arrives as NaN, the field clears itself, the clear
            counts as a change, and it is set again. The console fills without end with

                Trying to set non-integer value "NaN" to <vaadin-integer-field>. Clearing the value.

            and the page never becomes usable. Reproduced on Mateu 3.0-alpha.291, both in a deployed
            instance and here.

            Isolated both ways, which is what makes it the null and not the annotations or the
            @MasterDetail lists: changing exactly those three components to long/int and nothing
            else opens the form — Cancel, Save, five fields, no warnings at all — and restoring them
            hangs it again.

            Not worked around, because the nullability is the meaning. durationMs null means
            "inherit from the scenario's default" and 0 means "finish instantly"; both are real, and
            a primitive cannot express the first. The other two would survive becoming primitives,
            which fixes two thirds of a page.

            Enable this the moment a null numeric renders as an empty field. The test is written and
            passes today against a build with those three fields made primitive.
            """)
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
