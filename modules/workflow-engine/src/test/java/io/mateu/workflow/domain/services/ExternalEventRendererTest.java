package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.PublishEvent;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.infra.config.EventDestinationsProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalEventRendererTest {

    private static final Step STEP = new Step("announce", "wd", StepType.PUBLISH_EVENT, "Announce", null, "start",
            null, null, false, null, null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);

    private static Process process(String businessKey) {
        return Process.builder().id("p-1").businessKey(businessKey).workflowDefinitionId("wd")
                .variables(List.of(new Variable("bookingId", "B-1"), new Variable("total", "80"))).build();
    }

    private static EventDestinationsProperties destinations(String... names) {
        var properties = new EventDestinationsProperties();
        for (var name : names) {
            var destination = new EventDestinationsProperties.Destination();
            destination.setTopic(name + "-topic");
            properties.getDestinations().put(name, destination);
        }
        return properties;
    }

    private static Step with(PublishEvent event) {
        return STEP.withEvent(event);
    }

    @Test
    void rendersPayloadKeyAndIdentity() {
        var event = ExternalEventRenderer.render(with(new PublishEvent("bookings", "booking.confirmed", "${bookingId}",
                Map.of("id", "${bookingId}", "total", "${total * 1}"), null, null, null)),
                process("BK-1"), "se-1", destinations("bookings"), true);
        assertThat(event.eventId()).isEqualTo("se-1");
        assertThat(event.key()).isEqualTo("B-1");
        assertThat(event.data()).isEqualTo("{\"id\":\"B-1\",\"total\":80}");
        assertThat(event.businessKey()).isEqualTo("BK-1");
        assertThat(event.stepId()).isEqualTo("announce");
        assertThat(event.partitionKey()).isEqualTo("B-1");
    }

    @Test
    void theKeyDefaultsToTheBusinessKeyThenTheProcess() {
        var noKey = with(new PublishEvent("d", "t", null, null, null, List.of("bookingId"), null));
        assertThat(ExternalEventRenderer.render(noKey, process("BK-2"), "se", null, false).key()).isEqualTo("BK-2");
        var event = ExternalEventRenderer.render(noKey, process(null), "se", null, false);
        assertThat(event.key()).isEqualTo("p-1");
        assertThat(event.data()).isEqualTo("{\"bookingId\":\"B-1\"}");
        assertThat(ExternalEventRenderer.render(with(new PublishEvent("d", "t", null, null, null, null, null)),
                process(null), "se", null, false).data()).isEqualTo("{}");
        assertThat(ExternalEventRenderer.render(with(new PublishEvent("d", "t", null, null, "{\"x\":\"${bookingId}\"}", null, "plain")),
                process(null), "se", null, false).data()).isEqualTo("{\"x\":\"B-1\"}");
    }

    @Test
    void destinationsAreEnforcedInKafkaModeAndOnceConfigured() {
        var step = with(new PublishEvent("unknown", "t", null, null, null, null, null));
        assertThatThrownBy(() -> ExternalEventRenderer.render(step, process(null), "se", destinations(), true))
                .hasMessageContaining("not configured");
        assertThatThrownBy(() -> ExternalEventRenderer.render(step, process(null), "se", destinations("other"), false))
                .hasMessageContaining("not configured");
        assertThat(ExternalEventRenderer.render(step, process(null), "se", destinations(), false)).isNotNull();
    }

    @Test
    void misconfiguredStepsFail() {
        assertThatThrownBy(() -> ExternalEventRenderer.render(STEP, process(null), "se", null, false))
                .hasMessageContaining("destination and a type");
        assertThatThrownBy(() -> ExternalEventRenderer.render(with(new PublishEvent("d", "t", null, Map.of(), "x", null, null)),
                process(null), "se", null, false)).hasMessageContaining("more than one");
        assertThatThrownBy(() -> ExternalEventRenderer.render(with(new PublishEvent("d", "t", null, null, null, null, "xml")),
                process(null), "se", null, false)).hasMessageContaining("format");
        var small = destinations();
        small.setMaxPayloadBytes(5);
        assertThatThrownBy(() -> ExternalEventRenderer.render(with(new PublishEvent("d", "t", null, null, null, List.of("bookingId"), null)),
                process(null), "se", small, false)).hasMessageContaining("limit");
    }
}
