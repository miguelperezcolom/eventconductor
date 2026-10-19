package io.mateu.workflow.uie2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.uie2e.support.AbstractUiE2eTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * A run a CONTINUE_AS_NEW handed over, as an operator sees it: the badge says it continued, the detail names
 * the run that carries on, and the diagram draws the CONTINUE_AS_NEW step.
 */
class ContinueAsNewJourneyTest extends AbstractUiE2eTest {

    @Test
    void aContinuedRunNamesTheRunThatCarriesOn() {
        var first = startProcess("ui-continue-as-new", "ui-can-1", new Variable("round", "1"));
        awaitUntil(Duration.ofSeconds(20), () -> processes.findAllByBusinessKey("ui-can-1").stream()
                .filter(run -> run.getStatus() == ProcessStatus.COMPLETED).count() == 2);
        var next = processes.findById(first).orElseThrow().getContinuedAsProcessId();

        var detail = ui.goToProcesses().open(first);
        assertThat(detail.tabShows("Continued as new")).isVisible();
        assertThat(detail.tabShows(next)).isVisible();

        detail.openTab("Diagram");
        assertThat(detail.node("Run again")).isVisible();
    }
}
