package io.mateu.workflow.dtos.events.integration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;

import java.util.List;

/**
 * External message aimed at a running process (BPMN message catch / Temporal signal).
 * The engine correlates it against MESSAGE steps waiting on {@code messageName}: the
 * process whose correlation key (businessKey by default, or the step's
 * {@code correlationExpression}) equals {@code correlationKey} absorbs {@code variables}
 * and resumes. Messages that match no waiting step are ignored, not buffered.
 *
 * @param origin the domain event this message was made from (a {@code WAIT_FOR_MESSAGE}'s
 *               {@code onEvent}), or null for a message sent as such. When present, the engine
 *               records the event's receipt only once a waiting step actually takes the message,
 *               so a redelivery of an event that arrived before the step was waiting is not
 *               mistaken for a duplicate, and a redelivery of one already taken is.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageReceived(String messageName, String correlationKey, List<Variable> variables,
                              @JsonInclude(JsonInclude.Include.NON_NULL) EventOrigin origin) implements DomainEvent {

    @JsonCreator
    public MessageReceived {
    }

    /** A message sent as such — not made from a domain event. */
    public MessageReceived(String messageName, String correlationKey, List<Variable> variables) {
        this(messageName, correlationKey, variables, null);
    }

    /**
     * The CloudEvents context of the domain event a message was made from: what its receipt is keyed
     * and listed by.
     */
    public record EventOrigin(String destination, String type, String source, String id) {
    }
}
