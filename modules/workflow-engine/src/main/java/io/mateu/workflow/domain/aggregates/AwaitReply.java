package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * {@code HTTP_CALL} and {@code PUBLISH_EVENT} only: the answer the step waits for after sending its
 * request — the request/response of an asynchronous exchange in one step. Carried in the {@code .ec}
 * file as the step's {@code awaitReply} block.
 *
 * <p>The fields mean exactly what they mean on a {@code WAIT_FOR_MESSAGE}: the step waits for the
 * message {@code messageName} whose correlation key equals {@code correlationExpression} over the
 * process, and {@code onEvent} lets a domain event be that message. The difference is when the wait is
 * armed: in the same unit of work that sends the request (the outbox row of the event, the dispatch of
 * the HTTP task), so a reply can never arrive before the step is waiting for it. The step's own
 * {@code timeout}, {@code deadline}, {@code retries} and {@code onTimeoutStepId} bound the whole
 * exchange, request and reply.
 *
 * @param messageName           the message the step waits for
 * @param correlationExpression JEXL over the process for the key the reply must carry ({@code businessKey},
 *                              {@code requestId}, …)
 * @param onEvent               optional: a domain event that is the reply (see {@link EventWait})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"messageName", "correlationExpression", "onEvent"})
public record AwaitReply(String messageName, String correlationExpression, EventWait onEvent) {

    public AwaitReply {
        messageName = messageName == null || messageName.isBlank() ? null : messageName.trim();
        correlationExpression = correlationExpression == null || correlationExpression.isBlank()
                ? null : correlationExpression;
    }
}
