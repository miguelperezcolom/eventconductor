package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.out.DeadLetterPublisher;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.ddd.DomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

/**
 * Parks an unprocessable event on the dead-letter topic.
 *
 * <p>The payload is the original event, untouched: replaying a dead letter is republishing it on
 * the destination its {@code x-dead-letter-source} header names, once whatever made it fail has
 * been dealt with. Everything about the failure travels in headers so the body stays replayable.
 *
 * <p>It keeps the process partition key, so a replayed event still reaches the pod that owns its
 * process, and so a burst of dead letters from one broken process stays on one partition instead
 * of spreading across all of them.
 */
@Service
@ConditionalOnProperty(name = "workflow.mode", havingValue = "kafka")
@RequiredArgsConstructor
@Slf4j
public class KafkaDeadLetterPublisher implements DeadLetterPublisher {

    static final String BINDING = "deadLetter";

    final StreamBridge streamBridge;
    final WorkflowMetrics workflowMetrics;

    @Override
    public void park(DomainEvent event, Throwable cause, String source) {
        log.error("Parking unprocessable event from {} on the dead-letter topic: {}", source, event, cause);
        workflowMetrics.eventDeadLettered(source);
        var message = MessageBuilder.withPayload(event)
                .setHeader("x-dead-letter-source", source)
                .setHeader("x-dead-letter-reason", cause.getClass().getName())
                .setHeader("x-dead-letter-message", String.valueOf(cause.getMessage()))
                .setHeader("x-dead-letter-process", String.valueOf(event.partitionKey()));
        var key = event.partitionKey();
        if (key != null && !key.isBlank()) {
            message.setHeader(org.springframework.kafka.support.KafkaHeaders.KEY,
                    key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        streamBridge.send(BINDING, message.build());
    }
}
