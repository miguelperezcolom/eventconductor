package com.example.series;

import com.example.series.agents.DemoAgentCatalogue;
import com.example.series.agents.DemoAgentRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.domain.aggregates.AgentCall;
import io.mateu.workflow.worker.api.TaskFailure;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The demo stand-ins on their own: the catalogue the engine checks against, the runtime's deterministic answers. */
class DemoAgentRuntimeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String run(String request) throws Exception {
        return DemoAgentRuntime.run(JSON.readTree(JSON.writeValueAsString(Map.of(
                "schemaVersion", 1, "agentRef", "front-desk-assistant", "outputVariable", "preparation",
                "taskPrompt", "Prepare what the front desk needs for this request: " + request,
                "input", Map.of("guest", "Erin", "room", "204", "request", request)))));
    }

    @Test
    void aWheelchairNeedsStaffAndTheSameRequestAlwaysGetsTheSameAnswer() throws Exception {
        var first = run("I use a wheelchair");
        assertThat(first).isEqualTo(run("I use a wheelchair"));
        var result = JSON.readTree(first);
        assertThat(result.path("category").asText()).isEqualTo("accessibility");
        assertThat(result.path("needsStaff").asBoolean()).isTrue();
        assertThat(result.path("actions").toString()).contains("room 204");
    }

    @Test
    void aLateArrivalNeedsNobody() throws Exception {
        var result = JSON.readTree(run("We will arrive late"));
        assertThat(result.path("category").asText()).isEqualTo("arrival");
        assertThat(result.path("needsStaff").asBoolean()).isFalse();
    }

    @Test
    void anAgentTheRuntimeDoesNotRunFails() {
        assertThatThrownBy(() -> DemoAgentRuntime.run(JSON.readTree("{\"agentRef\": \"nobody\"}")))
                .isInstanceOf(TaskFailure.class).hasMessageContaining("nobody");
    }

    @Test
    void theCataloguePublishesFrontDeskAssistantWithAnOutputSchema() {
        var catalogue = new DemoAgentCatalogue();
        assertThat(catalogue.list()).extracting(a -> a.id()).containsExactly("front-desk-assistant");
        catalogue.validate(new AgentCall("front-desk-assistant", null, null, null, null, null, "x", null));
        assertThatThrownBy(() -> catalogue.validate(new AgentCall("nobody", null, null, null, null, null, "x", null)))
                .hasMessageContaining("Unknown published workflow agent");
    }
}
