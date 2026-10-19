package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class AgentTranscriptsTest {

    /** An agent that answered after one tool call, as the loop leaves it. */
    public static StepExecution agentExecution() {
        var transcript = MultiInstance.toJson(Map.of(
                "state", "answered", "turns", 2, "toolCalls", 1, "inputTokens", 20, "outputTokens", 10, "totalTokens", 30,
                "transcript", List.of(
                        Map.of("role", "user", "content", "Where is O-1?"),
                        Map.of("turn", 0, "role", "assistant", "content", "",
                                "toolCalls", List.of(Map.of("id", "c1", "name", "lookup", "arguments", "{\"id\":\"O-1\"}"))),
                        Map.of("turn", 0, "role", "tool", "tool", "lookup", "content", "{\"status\":\"SHIPPED\"}",
                                "outcome", "completed", "callId", "c1"),
                        Map.of("turn", 1, "role", "assistant", "content", "It shipped."))));
        return StepExecution.builder().id("agent-1").processId("p").stepId("triage")
                .stepJson("{\"id\":\"triage\",\"type\":\"AGENT_CALL\",\"name\":\"Triage\"}")
                .status(StepExecutionStatus.COMPLETED)
                .scopeJson("{\"__agentKind\":\"agent\",\"goal\":\"Where is O-1?\"}")
                .outputJson(transcript)
                .build();
    }

    @Test
    void theSummaryAndTheEntriesComeOffTheAgentsOwnExecution() {
        var agent = agentExecution();
        assertThat(AgentTranscripts.summary(agent)).containsEntry("state", "answered").containsEntry("turns", 2)
                .containsEntry("toolCalls", 1).containsEntry("totalTokens", 30).containsEntry("lastMessage", "It shipped.");
        assertThat(AgentTranscripts.entries(agent)).hasSize(4);
    }

    @Test
    void anythingElseHasNoTranscript() {
        var plain = StepExecution.builder().id("x").stepId("x").stepJson("{\"id\":\"x\",\"type\":\"ACTION\",\"name\":\"X\"}").build();
        assertThat(AgentTranscripts.summary(plain)).isNull();
        assertThat(AgentTranscripts.entries(plain)).isEmpty();
        assertThat(AgentTranscripts.summary(null)).isNull();
        var unreadable = agentExecution().toBuilder().outputJson("{not json").build();
        assertThat(AgentTranscripts.summary(unreadable)).isNull();
        var fresh = agentExecution().toBuilder().outputJson(null).build();
        assertThat(AgentTranscripts.summary(fresh)).isNull();
    }
}
