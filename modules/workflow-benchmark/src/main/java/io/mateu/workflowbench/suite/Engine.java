package io.mateu.workflowbench.suite;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.sync.SyncInvocationService;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventUseCase;
import io.mateu.workflowbench.suite.jpa.SuiteJpaApp;
import io.mateu.workflowbench.suite.memory.SuiteMemoryApp;
import io.mateu.workflowbench.suite.worker.SuiteKafkaWorkerApp;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * One deployment mode of the engine, booted in this JVM: how load gets in, and how to read back
 * what happened.
 *
 * <p>Load enters the way it does in that mode for real. Embedded: the caller's thread calls
 * {@link ProcessUpstreamEventUseCase} (what an application embedding the engine does). Kafka: a
 * producer publishes {@code ProcessCreationRequested} / {@code MessageReceived} to the
 * {@code upstream} topic (what any external system does) — the driver never calls into a pod.
 * Synchronous invocations go through {@link SyncInvocationService} on the first pod in every mode,
 * which is what the REST endpoint calls, minus HTTP.
 */
abstract class Engine implements AutoCloseable {

    final String mode;
    final Map<String, Object> settings = new TreeMap<>();
    final List<ConfigurableApplicationContext> contexts = new ArrayList<>();

    Engine(String mode) {
        this.mode = mode;
    }

    abstract void create(String definitionId, String businessKey, List<Variable> variables);

    abstract void message(String name, String correlationKey);

    /** True when {@link #create} only enqueues (kafka) — the driver then needs no threads of its own. */
    abstract boolean asynchronousIngress();

    void flush() {
    }

    abstract Probe probe();

    SyncInvocationService sync() {
        return contexts.getFirst().getBean(SyncInvocationService.class);
    }

    @Override
    public void close() {
        // Pods first, then the worker: a worker closed under running pods only makes noise.
        for (var i = contexts.size() - 1; i >= 0; i--) {
            try {
                contexts.get(i).close();
            } catch (RuntimeException e) {
                System.err.println("closing " + mode + " context: " + e);
            }
        }
    }

    /** What every engine context gets, whatever the mode. */
    static Map<String, Object> common() {
        var props = new LinkedHashMap<String, Object>();
        props.put("spring.main.web-application-type", "none");
        props.put("spring.main.banner-mode", "off");
        props.put("logging.level.root", "WARN");
        props.put("logging.level.io.mateu.workflow", "WARN");
        props.put("workflow.cron-enabled", "false");
        // Passthrough: -Dsuite.engine.<property>=<value> reaches every engine context, and the
        // report prints it back — a sweep needs no recompile and cannot go unrecorded.
        System.getProperties().forEach((k, v) -> {
            var key = k.toString();
            if (key.startsWith("suite.engine.")) {
                props.put(key.substring("suite.engine.".length()), v.toString());
            }
        });
        return props;
    }

    /** The JPA settings the shipped orchestrator app uses (apps/orchestrator-standalone-app). */
    static Map<String, Object> jpa(String jdbcUrl) {
        var props = new LinkedHashMap<String, Object>();
        props.put("workflow.persistence", "jpa");
        props.put("spring.datasource.url", jdbcUrl);
        props.put("spring.datasource.username", "suite");
        props.put("spring.datasource.password", "suite");
        props.put("spring.datasource.hikari.maximum-pool-size", System.getProperty("suite.pool-size", "10"));
        props.put("spring.jpa.hibernate.ddl-auto", "update");
        props.put("spring.jpa.open-in-view", "false");
        props.put("spring.jpa.properties.hibernate.jdbc.batch_size", "20");
        props.put("spring.jpa.properties.hibernate.order_inserts", "true");
        props.put("spring.jpa.properties.hibernate.order_updates", "true");
        props.put("spring.flyway.enabled", "false");
        props.put("workflow.schema.enabled", "true");
        return props;
    }

    static ConfigurableApplicationContext boot(Class<?> app, Map<String, Object> props) {
        return new SpringApplicationBuilder(app).web(WebApplicationType.NONE).properties(props).run();
    }

    /** Settings worth printing: everything the suite set, minus credentials and the per-run URLs. */
    void record(String prefix, Map<String, Object> props) {
        props.forEach((k, v) -> {
            if (!k.contains("password") && !k.contains("url") && !k.contains("brokers")) {
                settings.put(prefix + k, v);
            }
        });
    }

    // ------------------------------------------------------------------ embedded

    static final class Embedded extends Engine {

        private final ProcessUpstreamEventUseCase upstream;
        private final Probe probe;

        Embedded(String mode, String jdbcUrl) {
            super(mode);
            var props = common();
            props.put("spring.application.name", "suite-" + mode);
            props.put("workflow.mode", "embedded");
            if (jdbcUrl == null) {
                props.put("workflow.persistence", "memory");
                // JDBC and JPA are on this classpath for the other modes; memory mode has no database.
                props.put("spring.autoconfigure.exclude", String.join(",",
                        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
                        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
                        "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"));
            } else {
                props.putAll(jpa(jdbcUrl));
            }
            record("", props);
            var context = boot(jdbcUrl == null ? SuiteMemoryApp.class : SuiteJpaApp.class, props);
            contexts.add(context);
            upstream = context.getBean(ProcessUpstreamEventUseCase.class);
            probe = jdbcUrl == null
                    ? new Probe.Memory(context.getBean(ProcessRepository.class), context.getBean(StepExecutionRepository.class))
                    : new Probe.Jdbc(context.getBean(org.springframework.jdbc.core.JdbcTemplate.class));
        }

        @Override
        void create(String definitionId, String businessKey, List<Variable> variables) {
            upstream.handle(new ProcessUpstreamEventCommand(new ProcessCreationRequested(definitionId, businessKey, variables)));
        }

        @Override
        void message(String name, String correlationKey) {
            upstream.handle(new ProcessUpstreamEventCommand(new MessageReceived(name, correlationKey, List.of())));
        }

        @Override
        boolean asynchronousIngress() {
            return false;
        }

        @Override
        Probe probe() {
            return probe;
        }
    }

    // ------------------------------------------------------------------ kafka

    static final class Kafka extends Engine {

        static final List<String> TOPICS = List.of("upstream", "downstream", "outbox", "dead-letter");

        private final KafkaProducer<byte[], String> producer;
        private final ObjectMapper json = new ObjectMapper();
        private final Probe probe;

        Kafka(String mode, int pods, String jdbcUrl, String brokers, Infra infra) {
            super(mode);
            infra.freshTopics(TOPICS);
            var concurrency = System.getProperty("suite.consumer-concurrency", "3");
            var workerProps = common();
            workerProps.put("spring.application.name", "suite-worker");
            workerProps.put("workflow.mode", "kafka");
            workerProps.put("spring.cloud.function.definition", "consumeWorkerEvent");
            workerProps.put("spring.cloud.stream.kafka.binder.brokers", brokers);
            workerProps.put("spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination", "downstream");
            workerProps.put("spring.cloud.stream.bindings.consumeWorkerEvent-in-0.group", "suite-worker");
            workerProps.put("spring.cloud.stream.bindings.consumeWorkerEvent-in-0.consumer.concurrency",
                    System.getProperty("suite.worker-concurrency", "6"));
            workerProps.put("spring.cloud.stream.bindings.upstream.destination", "upstream");
            record("worker: ", workerProps);
            contexts.add(boot(SuiteKafkaWorkerApp.class, workerProps));

            for (var i = 0; i < pods; i++) {
                var props = common();
                props.put("spring.application.name", "suite-pod-" + i);
                props.put("workflow.mode", "kafka");
                props.putAll(jpa(jdbcUrl));
                props.put("spring.cloud.function.definition", "consumeOutbox;consumeUpstream");
                props.put("spring.cloud.stream.kafka.binder.brokers", brokers);
                props.put("spring.cloud.stream.bindings.consumeOutbox-in-0.destination", "outbox");
                props.put("spring.cloud.stream.bindings.consumeOutbox-in-0.group", "orchestrator-outbox");
                props.put("spring.cloud.stream.bindings.consumeOutbox-in-0.consumer.batch-mode", "true");
                props.put("spring.cloud.stream.bindings.consumeOutbox-in-0.consumer.concurrency", concurrency);
                props.put("spring.cloud.stream.bindings.consumeUpstream-in-0.destination", "upstream");
                props.put("spring.cloud.stream.bindings.consumeUpstream-in-0.group", "orchestrator-upstream");
                props.put("spring.cloud.stream.bindings.consumeUpstream-in-0.consumer.batch-mode", "true");
                props.put("spring.cloud.stream.bindings.consumeUpstream-in-0.consumer.concurrency", concurrency);
                props.put("spring.cloud.stream.bindings.upstream.destination", "upstream");
                props.put("spring.cloud.stream.bindings.outbox.destination", "outbox");
                props.put("spring.cloud.stream.bindings.downstream.destination", "downstream");
                props.put("spring.cloud.stream.bindings.deadLetter.destination", "dead-letter");
                if (i == 0) {
                    record("pod: ", props);
                }
                contexts.add(boot(SuiteJpaApp.class, props));
            }
            settings.put("pods", pods);
            settings.put("topic partitions", Infra.PARTITIONS);

            var producerProps = new Properties();
            producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
            producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
            producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            producerProps.put(ProducerConfig.LINGER_MS_CONFIG, "0");
            producer = new KafkaProducer<>(producerProps);
            probe = new Probe.Jdbc(contexts.get(1).getBean(org.springframework.jdbc.core.JdbcTemplate.class));
        }

        @Override
        SyncInvocationService sync() {
            return contexts.get(1).getBean(SyncInvocationService.class); // the first pod (0 is the worker)
        }

        @Override
        void create(String definitionId, String businessKey, List<Variable> variables) {
            send(new ProcessCreationRequested(definitionId, businessKey, variables));
        }

        @Override
        void message(String name, String correlationKey) {
            send(new MessageReceived(name, correlationKey, List.of()));
        }

        private void send(DomainEvent event) {
            try {
                var key = event.partitionKey();
                producer.send(new ProducerRecord<>("upstream",
                        key == null ? null : key.getBytes(StandardCharsets.UTF_8),
                        json.writerFor(DomainEvent.class).writeValueAsString(event)));
            } catch (Exception e) {
                throw new IllegalStateException("Could not publish " + event, e);
            }
        }

        @Override
        boolean asynchronousIngress() {
            return true;
        }

        @Override
        void flush() {
            producer.flush();
        }

        @Override
        Probe probe() {
            return probe;
        }

        @Override
        public void close() {
            producer.close();
            super.close();
        }
    }
}
