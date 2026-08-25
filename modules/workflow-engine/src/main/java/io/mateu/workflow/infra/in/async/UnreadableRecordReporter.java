package io.mateu.workflow.infra.in.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.DeadLetterPublisher;
import io.mateu.workflow.ddd.DomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.function.context.config.MessageConverterHelper;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Gives a voice to the one failure the engine had no voice for: a record whose bytes nothing could
 * turn into a {@link DomainEvent}.
 *
 * <p>Everything downstream of deserialization is already covered — a event that parses but cannot be
 * handled is parked by {@link OrchestratorKafkaConsumerConfig}, logged, and counted. A record that
 * does not parse never reaches that code, because it never becomes an event. Spring Cloud Stream
 * hands the bytes to the message converter, the converter fails, and the binder's
 * {@code DefaultMessageConverterHelper} answers {@code shouldFailIfCantConvert} with {@code false}
 * and then does nothing but re-align the {@code kafka_*} header lists so they still match the
 * records that did convert. The record is dropped, the batch commits, and the offset advances.
 *
 * <p>Which is silence in the most literal sense: not a log line at any level, no dead letter, no
 * metric, and a consumer-group lag of zero. A deployment that sends a thousand malformed messages
 * sees a healthy engine that created nothing, and the message content is the last place anyone
 * looks, because nothing points there.
 *
 * <p>Skipping is still the right behaviour and this does not change it — {@code shouldFailIfCantConvert}
 * stays {@code false}. A record that fails to parse will fail to parse forever, so failing the batch
 * would redeliver it forever and stall the partition for every process behind it. That is the same
 * judgement the consumer already makes for an unprocessable event: park it and keep going. This only
 * makes the skip say so.
 */
@Component
@ConditionalOnProperty(name = "workflow.mode", havingValue = "kafka")
@RequiredArgsConstructor
@Slf4j
public class UnreadableRecordReporter implements MessageConverterHelper {

    /** Enough of the payload to recognise the message; a batch of large records must not fill the log. */
    static final int PAYLOAD_EXCERPT = 2_000;

    final DeadLetterPublisher deadLetterPublisher;
    final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /**
     * The batch hook, and the one that matters: all three engine consumers take a {@code List}, so
     * every record they receive comes through here.
     *
     * <p>The index is not the record's position — it is how many records of the batch have converted
     * so far, which is what the binder's helper uses to keep the header lists aligned as it removes
     * entries. Rather than reconstruct a position from it, this re-reads the batch and reports every
     * record it cannot parse. The rereading costs nothing in the normal case because the normal case
     * never gets here, and it means the report does not depend on which helper ran first.
     */
    @Override
    public void postProcessBatchMessageOnFailure(Message<?> message, int index) {
        if (!(message.getPayload() instanceof List<?> records)) {
            return;
        }
        if (alreadyReported(message)) {
            return;
        }
        var reported = 0;
        for (var record : records) {
            var bytes = asBytes(record);
            if (bytes == null) {
                continue;
            }
            var cause = whyItCannotBeRead(bytes);
            if (cause != null) {
                report(bytes, cause, topicOf(message));
                reported++;
            }
        }
        if (reported == 0) {
            // Every record parses on a second look, so the conversion failed for a reason this cannot
            // reproduce. Say that too: an unexplained drop is still a drop, and still worth a line.
            log.error("A record in a batch from {} could not be converted, and re-reading the batch "
                    + "did not reproduce it. {} record(s) in the batch; none was parked.",
                    topicOf(message), records.size());
        }
    }

    /**
     * The single-record hook. The engine's own bindings are all batch bindings, so this covers a host
     * that turns {@code batch-mode} off, and the {@code workerReply} style bindings that are not
     * batches. Returning {@code false} keeps the record skipped rather than redelivered for ever.
     */
    @Override
    public boolean shouldFailIfCantConvert(Message<?> message, Throwable cause) {
        if (message.getPayload() instanceof List<?>) {
            // The batch path calls this immediately after the batch hook above, with a null cause.
            // It is the same failure, already reported with the record that caused it.
            return false;
        }
        var bytes = asBytes(message.getPayload());
        if (bytes != null) {
            report(bytes, cause != null ? cause : whyItCannotBeRead(bytes), topicOf(message));
        }
        return false;
    }

    private void report(byte[] payload, Throwable cause, String topic) {
        log.error("Unreadable record from {}: nothing could turn these bytes into an event, so it is "
                        + "parked on the dead-letter topic and skipped. Payload: {}",
                topic, excerpt(payload), cause);
        deadLetterPublisher.parkUnreadable(payload, cause, topic);
    }

    /**
     * The reason the bytes are not an event, obtained by parsing them here rather than by asking the
     * converter — the batch path reports its failures with a null cause, and "could not be converted"
     * without saying what was wrong with it is most of the way back to silence.
     */
    private Throwable whyItCannotBeRead(byte[] payload) {
        try {
            objectMapper.readValue(payload, DomainEvent.class);
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    private static byte[] asBytes(Object payload) {
        if (payload instanceof byte[] bytes) {
            return bytes;
        }
        if (payload instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        return null;
    }

    private static String excerpt(byte[] payload) {
        var text = new String(payload, StandardCharsets.UTF_8);
        return text.length() <= PAYLOAD_EXCERPT
                ? text
                : text.substring(0, PAYLOAD_EXCERPT) + "… (" + payload.length + " bytes in all)";
    }

    /**
     * The destination the record arrived on. In a batch the header is a list, one entry per record,
     * and entries are removed as records fail — but every record of a binding's batch comes from that
     * binding's destination, so the first entry names it whoever has trimmed what.
     */
    private static String topicOf(Message<?> message) {
        var topic = message.getHeaders().get("kafka_receivedTopic");
        if (topic instanceof List<?> topics && !topics.isEmpty()) {
            return String.valueOf(topics.get(0));
        }
        return topic == null ? "an unnamed topic" : String.valueOf(topic);
    }

    /**
     * One report per batch, however many of its records fail. Conversion of a batch happens on the
     * consumer thread that polled it, and the hook fires once per failing record, so remembering the
     * last batch seen on this thread is enough to keep a batch of a thousand bad records from being
     * re-read a thousand times.
     */
    private final ThreadLocal<Object> lastBatchReported = new ThreadLocal<>();

    private boolean alreadyReported(Message<?> message) {
        var payload = message.getPayload();
        if (lastBatchReported.get() == payload) {
            return true;
        }
        lastBatchReported.set(payload);
        return false;
    }
}
