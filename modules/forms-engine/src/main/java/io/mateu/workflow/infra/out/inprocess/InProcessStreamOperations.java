package io.mateu.workflow.infra.out.inprocess;

import io.mateu.workflow.application.services.HumanTaskEvents;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.inprocess.InProcessUpstream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.stream.function.StreamOperations;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.Message;
import org.springframework.util.MimeType;

/**
 * What the forms engine publishes, when the workflow engine is embedded in the same application and
 * there is no broker ({@code workflow.mode=embedded}): Spring Cloud Stream is switched off there, so
 * there is no {@code StreamBridge}, and this takes its place.
 *
 * <ul>
 *   <li>{@code upstream} — the reply to the engine ({@code TaskStatusChanged}) and timeline lines
 *       ({@code TaskLogEmitted}) — goes straight to the in-process engine ({@link InProcessUpstream}),
 *       which processes it on the calling thread. A failure there is thrown, so the reply's
 *       retry-or-throw path ({@code WorkerReply}) behaves as it does against a broker.</li>
 *   <li>{@code humanTasks} — the {@code HumanTaskChanged} of every task — is published as a Spring
 *       application event, for an {@code @EventListener} of the application.</li>
 * </ul>
 *
 * Any other binding is refused ({@code false}), as a broker refuses a destination it does not have.
 */
@Slf4j
public class InProcessStreamOperations implements StreamOperations {

    public static final String UPSTREAM = "upstream";

    private final ObjectProvider<InProcessUpstream> upstream;
    private final ApplicationEventPublisher applicationEvents;

    public InProcessStreamOperations(ObjectProvider<InProcessUpstream> upstream,
                                     ApplicationEventPublisher applicationEvents) {
        this.upstream = upstream;
        this.applicationEvents = applicationEvents;
    }

    @Override
    public boolean send(String bindingName, Object data) {
        var payload = data instanceof Message<?> message ? message.getPayload() : data;
        if (UPSTREAM.equals(bindingName)) {
            var engine = upstream.getIfAvailable();
            if (engine == null) {
                log.warn("No workflow engine in this application to take {} — is workflow-engine on the classpath, "
                        + "or should workflow.mode be kafka?", describe(payload));
                return false;
            }
            if (!(payload instanceof DomainEvent event)) {
                log.warn("Not an engine event, not sent upstream: {}", describe(payload));
                return false;
            }
            engine.publish(event);
            return true;
        }
        if (HumanTaskEvents.BINDING.equals(bindingName)) {
            applicationEvents.publishEvent(payload);
            return true;
        }
        log.debug("No in-process destination for binding {}; {} not sent", bindingName, describe(payload));
        return false;
    }

    @Override
    public boolean send(String bindingName, Object data, MimeType outputContentType) {
        return send(bindingName, data);
    }

    /** The binder name means nothing in process: there is only this one. */
    @Override
    public boolean send(String bindingName, String binderName, Object data) {
        return send(bindingName, data);
    }

    @Override
    public boolean send(String bindingName, String binderName, Object data, MimeType outputContentType) {
        return send(bindingName, data);
    }

    private static String describe(Object payload) {
        return payload == null ? "null" : payload.getClass().getSimpleName();
    }
}
