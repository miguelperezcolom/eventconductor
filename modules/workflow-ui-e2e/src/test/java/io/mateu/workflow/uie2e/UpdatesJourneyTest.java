package io.mateu.workflow.uie2e;

import io.mateu.workflow.application.updates.ProcessUpdateService;
import io.mateu.workflow.uie2e.support.AbstractUiE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * The updates a process received, as an operator reads them: the Updates tab lists each one — which, how it
 * ended, the payload as recorded (its sensitive fields masked, never the value) and what it answered or why
 * it was refused. Over the JPA store the console runs on (V55's table, validated against its entity).
 */
class UpdatesJourneyTest extends AbstractUiE2eTest {

    @Autowired
    ProcessUpdateService updates;

    @Test
    void theUpdatesTabShowsWhatEachUpdateDidAndHidesSecrets() {
        var id = startProcess("ui-updates", "ui-updates-1");
        awaitUntil(Duration.ofSeconds(20), () -> processes.findById(id).orElseThrow().getStatus()
                == io.mateu.workflow.domain.aggregates.ProcessStatus.RUNNING);
        updates.submitInline(new ProcessUpdateService.UpdateRequest(id, null, "changeAddress",
                "{\"address\": \"Elm Street 2\", \"pin\": \"4321\"}", null, null, null));
        updates.submitInline(new ProcessUpdateService.UpdateRequest(id, null, "changeAddress",
                "{\"address\": \"Nowhere\"}", null, null, null));

        var detail = ui.goToProcesses().open(id);
        detail.openTab("Updates");

        assertThat(detail.tabShows("Completed")).isVisible();
        assertThat(detail.tabShows("Refused")).isVisible();
        assertThat(detail.tabShows("Nowhere is not an address.")).isVisible();
        assertThat(detail.tabShows("{\"shipsTo\":\"Elm Street 2\"}")).isVisible();
        assertThat(detail.tabShows("{\"address\":\"Elm Street 2\",\"pin\":\"****\"}")).isVisible();
        assertThat(page.locator("text=4321 >> visible=true")).hasCount(0);
    }
}
