package io.mateu.workflow.application.usecases.correlatemessage;

import io.mateu.workflow.domain.aggregates.Variable;

import java.util.List;

/**
 * @param origin the domain event the message was made from ({@code onEvent}), or null for a message
 *               sent as such; see {@link io.mateu.workflow.dtos.events.integration.MessageReceived#origin()}
 */
public record CorrelateMessageCommand(String messageName, String correlationKey, List<Variable> variables,
                                      io.mateu.workflow.dtos.events.integration.MessageReceived.EventOrigin origin) {

    /** A message sent as such — not made from a domain event. */
    public CorrelateMessageCommand(String messageName, String correlationKey, List<Variable> variables) {
        this(messageName, correlationKey, variables, null);
    }
}
