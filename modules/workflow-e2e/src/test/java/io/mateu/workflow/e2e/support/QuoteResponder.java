package io.mateu.workflow.e2e.support;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;
import io.mateu.workflow.infra.out.events.ExternalEventPublished;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The fastest possible "other system" of an asynchronous request/response: it answers a quote
 * request the moment it sees it, on the thread that delivered the request — before the engine has
 * done anything else. If a workflow's reply can be lost, this responder loses it every time, which
 * turns a timing race into a deterministic test.
 *
 * <p>Requests arrive either as a PUBLISH_EVENT on {@code rr-quote-requests} (the application's event
 * publisher, embedded mode) or, for HTTP_CALL, from a test worker that calls {@link #reply} before
 * it reports the HTTP call as done — the remote system calling back before its 2xx is processed.
 */
public class QuoteResponder {

    public static final String MESSAGE = "quote-ready";
    /** An item nobody quotes: the request goes out and no reply ever comes. */
    public static final String SILENT = "nobody-sells-this";

    /** Requests on this destination are answered only from the second one per key on: the first is ignored. */
    public static final String SECOND_TIME = "rr-quote-requests-twice";
    /** Requests on this destination are answered with a domain event on {@link #REPLIES}, not a message. */
    public static final String BY_EVENT = "rr-quote-requests-by-event";
    /** Where the event replies go: an {@code onEvent} reply's destination. */
    public static final String REPLIES = "rr-quote-replies";

    private final ObjectProvider<ProcessUpstreamEventUseCase> upstream;
    private final ObjectProvider<io.mateu.workflow.application.services.InboundEventService> inbound;
    private final Map<String, Integer> replies = new ConcurrentHashMap<>();
    private final Map<String, List<String>> requestIds = new ConcurrentHashMap<>();

    public QuoteResponder(ObjectProvider<ProcessUpstreamEventUseCase> upstream,
                          ObjectProvider<io.mateu.workflow.application.services.InboundEventService> inbound) {
        this.upstream = upstream;
        this.inbound = inbound;
    }

    @EventListener
    public void on(ExternalEventPublished published) {
        var event = published.event();
        var silent = event.data().contains("\"item\":\"" + SILENT + "\"");
        switch (event.destination()) {
            case "rr-quote-requests" -> {
                if (!silent) {
                    reply(event.key());
                }
            }
            case SECOND_TIME -> {
                var ids = requestIds.computeIfAbsent(event.key(), k -> new java.util.concurrent.CopyOnWriteArrayList<>());
                ids.add(event.eventId());
                if (ids.size() >= 2) {
                    reply(event.key());
                }
            }
            case BY_EVENT -> {
                if (!silent) {
                    replyWithEvent(event.key(), "reply-to-" + event.eventId());
                }
            }
            default -> {
            }
        }
    }

    /** Answers with a domain event on {@link #REPLIES} carrying the request's id in its payload. */
    public void replyWithEvent(String correlationId, String eventId) {
        replies.merge(correlationId, 1, Integer::sum);
        inbound.getObject().receive(new io.mateu.workflow.domain.aggregates.InboundEvent(REPLIES, eventId,
                "pricing/e2e", "com.acme.quote.ready", null, null, null, "application/json",
                "{\"requestId\":\"" + correlationId + "\",\"price\":\"42\"}", Map.of()));
    }

    /** The CloudEvents ids of the requests seen on {@link #SECOND_TIME} for this correlation id, in order. */
    public List<String> requestIdsFor(String correlationId) {
        return List.copyOf(requestIds.getOrDefault(correlationId, List.of()));
    }

    /** Sends the reply the way any producer would: a message, correlated by the request's id. */
    public void reply(String correlationId) {
        replies.merge(correlationId, 1, Integer::sum);
        upstream.getObject().handle(new ProcessUpstreamEventCommand(
                new MessageReceived(MESSAGE, correlationId, List.of(new Variable("price", "42")))));
    }

    /** How many replies were sent for this correlation id. */
    public int repliesTo(String correlationId) {
        return replies.getOrDefault(correlationId, 0);
    }
}
