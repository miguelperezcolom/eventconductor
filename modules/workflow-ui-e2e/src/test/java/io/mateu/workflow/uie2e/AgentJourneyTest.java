package io.mateu.workflow.uie2e;

import io.mateu.workflow.uie2e.support.AbstractUiE2eTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An agent as an operator sees it: its model turns and tool calls are rows of the Steps tab, its
 * transcript is the Agent tab, and the diagram draws its tools hanging off it with how many turns and
 * calls it took.
 */
class AgentJourneyTest extends AbstractUiE2eTest {

    @Test
    void anAgentsTurnsToolCallsAndTranscriptAreThereToRead() {
        var id = startProcess("ui-agent", "ui-agent-1");
        awaitUntil(Duration.ofSeconds(20), () -> processes.findById(id).orElseThrow().getStatus()
                == io.mateu.workflow.domain.aggregates.ProcessStatus.COMPLETED);

        var detail = ui.goToProcesses().open(id);
        assertThat(detail.tabShows("agent · turn 1")).isVisible();
        assertThat(detail.tabShows("lookup · lookup call (turn 1)")).isVisible();

        detail.openTab("Agent");
        assertThat(detail.tabShows("Look the order up")).isVisible();
        assertThat(detail.tabShows("The order is on its way.")).isVisible();

        detail.openTab("Diagram");
        awaitUntil(Duration.ofSeconds(10), () -> !detail.agentShapes().isEmpty());
        assertThat(detail.agentShapes()).contains("tool-edge:agent->lookup", "agent:agent:2↻ 1⚒");
    }
}
