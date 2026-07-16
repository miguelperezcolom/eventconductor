package io.mateu.workflowdist.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.ddd.DomainEvent;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Singleton infrastructure for the distributed suite: one PostgreSQL and one Kafka
 * container (Testcontainers) shared by every test class, plus factories for orchestrator
 * and worker instances. Orchestrator "pods" are separate Spring application contexts in
 * this JVM wired to the real containers — each has its own Kafka consumers, connection
 * pool and background threads, so killing one (abruptly closing its context) leaves
 * durable state exactly as a process kill would: unsent rows stay {@code Pending} in the
 * outbox table and unconsumed events stay on the topics.
 */
public final class DistInfra {

    /** Advisory lock id used by the engine's OutboxRelay (see OutboxRelay.LOCK_ID). */
    public static final long OUTBOX_RELAY_LOCK_ID = 111222333L;

    private static final int PARTITIONS = 6;

    private static PostgreSQLContainer<?> postgres;
    private static KafkaContainer kafka;
    private static ConfigurableApplicationContext workerContext;
    private static KafkaProducer<String, String> producer;
    private static JdbcTemplate jdbc;
    private static int instanceCounter;

    private static final ObjectMapper eventWriter = new ObjectMapper();

    private DistInfra() {
    }

    public static synchronized void ensureStarted() {
        if (postgres != null) {
            return;
        }
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("workflow")
                .withUsername("workflow")
                .withPassword("secret");
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));
        postgres.start();
        kafka.start();
        createTopics();

        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        var producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producer = new KafkaProducer<>(producerProps);
    }

    /** Multiple partitions so two orchestrator pods in the same consumer group genuinely share traffic. */
    private static void createTopics() {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic("upstream", PARTITIONS, (short) 1),
                    new NewTopic("downstream", PARTITIONS, (short) 1),
                    new NewTopic("outbox", PARTITIONS, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not create Kafka topics", e);
        }
    }

    /**
     * Boots an orchestrator instance (kafka + jpa mode) against the shared containers.
     * The caller owns the context: close it to "kill the pod".
     */
    public static synchronized ConfigurableApplicationContext startOrchestrator(Map<String, Object> overrides) {
        ensureStarted();
        var props = new HashMap<String, Object>();
        props.put("spring.application.name", "dist-orchestrator-" + (++instanceCounter));
        props.put("spring.main.web-application-type", "none");
        props.put("spring.main.banner-mode", "off");
        props.put("workflow.mode", "kafka");
        props.put("workflow.persistence", "jpa");
        props.put("workflow.outbox-poll-interval-ms", "200");
        props.put("workflow.timeout-scan-interval-ms", "250");
        props.put("spring.datasource.url", postgres.getJdbcUrl());
        props.put("spring.datasource.username", postgres.getUsername());
        props.put("spring.datasource.password", postgres.getPassword());
        props.put("spring.datasource.hikari.maximum-pool-size", "10");
        props.put("spring.jpa.hibernate.ddl-auto", "update");
        props.put("spring.jpa.open-in-view", "false");
        props.put("spring.cloud.function.definition", "consumeOutbox;consumeUpstream");
        props.put("spring.cloud.stream.kafka.binder.brokers", kafka.getBootstrapServers());
        props.put("spring.cloud.stream.bindings.consumeOutbox-in-0.destination", "outbox");
        props.put("spring.cloud.stream.bindings.consumeOutbox-in-0.group", "orchestrator-group");
        props.put("spring.cloud.stream.bindings.consumeOutbox-in-0.consumer.concurrency", "3");
        props.put("spring.cloud.stream.bindings.consumeUpstream-in-0.destination", "upstream");
        props.put("spring.cloud.stream.bindings.consumeUpstream-in-0.group", "orchestrator-group");
        props.put("spring.cloud.stream.bindings.consumeUpstream-in-0.consumer.concurrency", "3");
        props.put("spring.cloud.stream.bindings.upstream.destination", "upstream");
        props.put("spring.cloud.stream.bindings.downstream.destination", "downstream");
        props.put("spring.cloud.stream.bindings.outbox.destination", "outbox");
        // No actuator/MeterRegistry in the test classpath.
        props.put("spring.autoconfigure.exclude",
                "io.mateu.workflow.autoconfigure.WorkflowMetricsAutoConfiguration");
        props.put("logging.level.io.mateu.workflow", "WARN");
        props.putAll(overrides);
        return new SpringApplicationBuilder(DistOrchestratorApp.class)
                .web(WebApplicationType.NONE)
                .properties(props)
                .run();
    }

    /** Boots (once) the shared Kafka worker context programmed through {@link WorkerStub}. */
    public static synchronized void ensureWorkerStarted() {
        ensureStarted();
        if (workerContext != null) {
            return;
        }
        var props = new HashMap<String, Object>();
        props.put("spring.application.name", "dist-worker");
        props.put("spring.main.web-application-type", "none");
        props.put("spring.main.banner-mode", "off");
        props.put("spring.cloud.function.definition", "consumeWorkerEvent");
        props.put("spring.cloud.stream.kafka.binder.brokers", kafka.getBootstrapServers());
        props.put("spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination", "downstream");
        props.put("spring.cloud.stream.bindings.consumeWorkerEvent-in-0.group", "worker-group");
        props.put("spring.cloud.stream.bindings.consumeWorkerEvent-in-0.consumer.concurrency", "3");
        props.put("spring.cloud.stream.bindings.upstream.destination", "upstream");
        workerContext = new SpringApplicationBuilder(DistWorkerApp.class)
                .web(WebApplicationType.NONE)
                .properties(props)
                .run();
    }

    /** Publishes a domain event onto the given topic exactly as an external system would. */
    public static void publishUpstream(DomainEvent event) {
        try {
            var json = eventWriter.writerFor(DomainEvent.class).writeValueAsString(event);
            producer.send(new ProducerRecord<>("upstream", json));
            producer.flush();
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish upstream event", e);
        }
    }

    /** Fire-and-forget variant for bulk publishing (call {@link #flushProducer()} afterwards). */
    public static void publishUpstreamAsync(DomainEvent event) {
        try {
            var json = eventWriter.writerFor(DomainEvent.class).writeValueAsString(event);
            producer.send(new ProducerRecord<>("upstream", json));
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish upstream event", e);
        }
    }

    public static void flushProducer() {
        producer.flush();
    }

    /** Direct JDBC access to the shared PostgreSQL for state assertions. */
    public static JdbcTemplate jdbc() {
        ensureStarted();
        return jdbc;
    }

    /**
     * Blocks every orchestrator's outbox relay by grabbing its PostgreSQL advisory lock
     * from a dedicated session. While held, domain events pile up as {@code Pending} rows —
     * the deterministic "crashed between committing a step and dispatching the next" window
     * DIST-02 needs. Returns the holding connection; pass it to {@link #unblockOutboxRelay}.
     */
    public static Connection blockOutboxRelay() {
        try {
            var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_lock(" + OUTBOX_RELAY_LOCK_ID + ")");
            }
            return connection;
        } catch (Exception e) {
            throw new IllegalStateException("Could not acquire outbox relay lock", e);
        }
    }

    public static void unblockOutboxRelay(Connection connection) {
        try (connection; var statement = connection.createStatement()) {
            statement.execute("SELECT pg_advisory_unlock(" + OUTBOX_RELAY_LOCK_ID + ")");
        } catch (Exception e) {
            throw new IllegalStateException("Could not release outbox relay lock", e);
        }
    }
}
