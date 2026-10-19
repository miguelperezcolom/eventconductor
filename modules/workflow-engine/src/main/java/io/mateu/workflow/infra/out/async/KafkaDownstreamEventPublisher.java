package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.ddd.DomainEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Service;

/**
 * Sends a task to the destination its step names.
 *
 * <p>A step's {@code topic} is the binding this sends on, and a step that names none goes to
 * {@code downstream} — the single destination everything used to go to, and the one the engine
 * contributes a binding for. A topic that has no binding of its own is a dynamic destination:
 * Spring Cloud Stream creates it on first use, so a definition can name a worker pool without the
 * application declaring anything, and it inherits the producer defaults (including
 * {@code sync=true}, without which the accepted/refused answer {@link PartitionedEvents} checks
 * would be meaningless).
 */
@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "kafka")
@RequiredArgsConstructor
public class KafkaDownstreamEventPublisher implements DownstreamEventPublisher {

    private final StreamBridge streamBridge;
    private final io.mateu.workflow.application.out.WorkflowTracing workflowTracing;
    /** Seals the sensitive variables an event carries before it reaches the broker or an HTTP worker's queue. */
    private final io.mateu.workflow.application.services.VariableProtection protection;

    /** Workers over HTTP: asked first, takes the tasks of the topics served over HTTP. */
    private org.springframework.beans.factory.ObjectProvider<HttpWorkerRouter> httpWorkers;

    @org.springframework.beans.factory.annotation.Autowired
    void httpWorkers(org.springframework.beans.factory.ObjectProvider<HttpWorkerRouter> httpWorkers) {
        this.httpWorkers = httpWorkers;
    }

    @Override
    public void publish(DomainEvent event, String topic) {
        // Sealed once: a Kafka worker and an HTTP worker are outside the engine alike.
        event = VariableProtectionSupport.seal(protection, event);
        if (HttpWorkerRouter.taken(httpWorkers, event, topic)) {
            return;
        }
        PartitionedEvents.send(streamBridge, DownstreamEventPublisher.destinationFor(topic), event,
                workflowTracing.currentTraceContext());
    }
}
