package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.ddd.DomainEvent;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;

import java.nio.charset.StandardCharsets;

/**
 * Publishes an event on the partition that belongs to its process.
 *
 * <p>This is the whole mechanism behind ownership: the event's {@link DomainEvent#partitionKey()}
 * becomes the Kafka message key, so every event of a process hashes to the same partition, and a
 * consumer group hands each partition to exactly one pod. Serialization per process — and
 * ordering, which an unkeyed topic never gave — stop being something the engine arranges with
 * locks and become a property of how the events are addressed.
 *
 * <p>An event with no key is sent unkeyed and lands wherever the partitioner puts it, which is
 * what every event did before. That is the deliberate fallback for events that belong to no
 * process, and for workers that report back without echoing one.
 *
 * <p>The key goes out as bytes rather than a String so it works under the binder's default
 * {@code ByteArraySerializer}, without every application having to configure a key serializer.
 */
final class PartitionedEvents {

    static boolean send(StreamBridge streamBridge, String binding, DomainEvent event) {
        var key = event.partitionKey();
        if (key == null || key.isBlank()) {
            return streamBridge.send(binding, event);
        }
        return streamBridge.send(binding, MessageBuilder.withPayload(event)
                .setHeader(KafkaHeaders.KEY, key.getBytes(StandardCharsets.UTF_8))
                .build());
    }

    private PartitionedEvents() {
    }
}
