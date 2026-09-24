package io.mateu.workflow.domain.aggregates;

import java.util.List;

/**
 * What a {@code PUBLISH_EVENT} step publishes. Carried in the {@code .ec} file as the step's
 * {@code event} block.
 *
 * @param destination      a logical destination name, mapped to a topic by
 *                         {@code workflow.events.destinations.<name>} — never a topic itself
 * @param type             the event type (CloudEvents {@code type}), e.g. {@code com.acme.booking.confirmed}
 * @param key              optional template for the message key; default the business key, else the process id
 * @param payload          a structured payload template (JSON/YAML with {@code ${…}} leaves)
 * @param payloadTemplate  a text payload template (the payload is then that text)
 * @param payloadVariables the payload is an object of these process variables
 * @param format           {@code binary} (default) or {@code structured} CloudEvents, or {@code plain};
 *                         null takes the destination's format
 */
public record PublishEvent(
        String destination,
        String type,
        String key,
        Object payload,
        String payloadTemplate,
        List<String> payloadVariables,
        String format
) {

    /** How many of the three payload sources are declared (at most one is allowed). */
    public int payloadSources() {
        return (payload != null ? 1 : 0)
                + (payloadTemplate != null && !payloadTemplate.isBlank() ? 1 : 0)
                + (payloadVariables != null && !payloadVariables.isEmpty() ? 1 : 0);
    }
}
