package io.mateu.workflowbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/**
 * Publishes load straight onto the broker, with no engine in the process.
 *
 * <p>Which is the point: a driver that has to start an orchestrator to publish can only measure a
 * machine that is also running one. Separating them is what lets the pods, the broker, the
 * database and the load run on four different hosts — the arrangement any figure worth putting
 * next to somebody else's has to come from.
 *
 * <p>It keys each event by the business key exactly as the engine does, so creation events are
 * distributed across partitions the same way they would be in production rather than piling onto
 * one.
 */
final class LoadDriver implements AutoCloseable {

    private final ObjectMapper json = new ObjectMapper();
    private final KafkaProducer<byte[], String> producer;

    LoadDriver(BenchmarkConfig config) {
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBrokers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // Nothing here should batch on the driver's side: the arrival rate is the experiment,
        // and a producer holding messages back to fill a batch would flatten it.
        props.put(ProducerConfig.LINGER_MS_CONFIG, "0");
        producer = new KafkaProducer<>(props);
    }

    void createProcess(String businessKey) {
        var event = new ProcessCreationRequested("bench-3-steps", businessKey, List.of(), null);
        producer.send(new ProducerRecord<>("upstream", key(event), serialize(event)));
    }

    void flush() {
        producer.flush();
    }

    private byte[] key(DomainEvent event) {
        var key = event.partitionKey();
        return key == null ? null : key.getBytes(StandardCharsets.UTF_8);
    }

    private String serialize(DomainEvent event) {
        try {
            return json.writerFor(DomainEvent.class).writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize " + event, e);
        }
    }

    @Override
    public void close() {
        producer.close();
    }
}
