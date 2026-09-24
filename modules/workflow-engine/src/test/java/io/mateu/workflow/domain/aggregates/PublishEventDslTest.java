package io.mateu.workflow.domain.aggregates;

import io.mateu.core.infra.JsonSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublishEventDslTest {

    private static Step step(String id, StepType type, String precondition) {
        return new Step(id, "wd-1", type, id, null, precondition, null, null, false, null, null, null,
                null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
    }

    private static WorkflowDefinition definition(PublishEvent event) {
        return new WorkflowDefinition("wd-1", "Pub", 1, null, false, 0, false, null, 0,
                List.of(step("start", StepType.START, null), step("pub", StepType.PUBLISH_EVENT, "start").withEvent(event)));
    }

    @Test
    void theEventBlockRoundTrips() {
        var event = new PublishEvent("bookings", "t", "${k}", Map.of("a", "${b}"), null, null, "plain");
        var back = JsonSerializer.pojoFromJson(JsonSerializer.toJson(step("pub", StepType.PUBLISH_EVENT, "start")
                .withEvent(event)), Step.class);
        assertThat(back.event()).isEqualTo(event);
        definition(event).checkInvariants();
    }

    @Test
    void theInvariantsRejectWhatCouldNeverPublish() {
        assertThatThrownBy(() -> definition(null).checkInvariants()).hasMessageContaining("destination and a type");
        assertThatThrownBy(() -> definition(new PublishEvent("d", null, null, null, null, null, null)).checkInvariants())
                .hasMessageContaining("destination and a type");
        assertThatThrownBy(() -> definition(new PublishEvent("d", "t", null, Map.of(), "x", null, null)).checkInvariants())
                .hasMessageContaining("more than one");
        assertThatThrownBy(() -> definition(new PublishEvent("d", "t", null, null, null, null, "xml")).checkInvariants())
                .hasMessageContaining("format");
        assertThatThrownBy(() -> definition(new PublishEvent("d", "t", "${k +}", null, null, null, null)).checkInvariants())
                .hasMessageContaining("event.key");
    }
}
