package io.mateu.workflow.projector;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.events.domain.ProcessStatusChanged;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.processindex.JdbcProcessIndexStore;
import io.mateu.workflow.schema.ManagedSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * The projector's consumer against a real read database: a batch of events in, an index out.
 *
 * <p>The claims worth holding it to are the ones the deployment rests on — that the index it builds
 * spans every shard, that replaying the topic rebuilds it (the reason the topic is compacted and the
 * reason the read database can be treated as disposable), and that a redelivered or out-of-order batch
 * changes nothing.
 */
class ProjectorConsumerTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 18, 10, 0);

    private DataSource dataSource;
    private ProcessIndexRepository store;
    private Consumer<List<DomainEvent>> consumer;

    @BeforeEach
    void freshReadDatabase() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:projector-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        migrate();
        store = new JdbcProcessIndexStore(dataSource);
        consumer = new ProjectorConfiguration().consumeProcessIndex(store);
    }

    private void migrate() {
        new ManagedSchema("process-index", "classpath:db/migration/processindex",
                "eventconductor_process_index_history").migrate(dataSource);
    }

    @Test
    void buildsOneIndexOutOfEveryShardsEvents() {
        consumer.accept(List.of(
                statusChanged("p1", "k1", "RUNNING", 20, T0, "s0"),
                statusChanged("p2", "k2", "RUNNING", 20, T0, "s1"),
                statusChanged("p3", "k3", "COMPLETED", 100, T0, "s2")));

        assertThat(store.countByStatus()).isEqualTo(Map.of("RUNNING", 2L, "COMPLETED", 1L));
        // The shard rides the event, stamped by its owner — which is what a command router reads back.
        assertThat(store.findByProcessId("p2").orElseThrow().shardId()).isEqualTo("s1");
    }

    @Test
    void replayingTheTopicRebuildsTheWholeIndex() {
        List<DomainEvent> topic = List.of(
                statusChanged("p1", "k1", "PENDING", 0, T0, "s0"),
                statusChanged("p1", "k1", "RUNNING", 50, T0.plusSeconds(1), "s0"),
                statusChanged("p1", "k1", "COMPLETED", 100, T0.plusSeconds(2), "s0"),
                statusChanged("p2", "k2", "RUNNING", 50, T0.plusSeconds(3), "s1"));
        consumer.accept(topic);
        var before = store.findByProcessId("p1").orElseThrow();

        // The read database is lost. Recreate the schema and replay from the earliest offset.
        dropReadDatabase();
        migrate();
        var rebuilt = new JdbcProcessIndexStore(dataSource);
        new ProjectorConfiguration().consumeProcessIndex(rebuilt).accept(topic);

        assertThat(rebuilt.findByProcessId("p1")).contains(before);
        assertThat(rebuilt.countByStatus()).isEqualTo(Map.of("COMPLETED", 1L, "RUNNING", 1L));
    }

    @Test
    void aRedeliveredBatchInAnyOrderLeavesTheSameIndex() {
        List<DomainEvent> batch = List.of(
                statusChanged("p1", "k1", "COMPLETED", 100, T0.plusSeconds(2), "s0"),
                statusChanged("p1", "k1", "PENDING", 0, T0, "s0"));
        consumer.accept(batch);
        consumer.accept(batch);
        consumer.accept(batch.reversed());

        assertThat(store.findByProcessId("p1").orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(store.countByStatus()).isEqualTo(Map.of("COMPLETED", 1L));
    }

    @Test
    void anEventOfAnotherKindIsIgnoredRatherThanStallingThePartition() {
        assertThatNoException().isThrownBy(() -> consumer.accept(List.of(
                new MessageReceived("wrong-channel", "k1", java.util.List.of()),
                statusChanged("p1", "k1", "RUNNING", 20, T0, "s0"))));

        assertThat(store.findByProcessId("p1")).isPresent();
    }

    /** The read database is lost — index, migration history and all. */
    private void dropReadDatabase() {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ProcessStatusChanged statusChanged(String processId, String businessKey, String status,
                                                      int completion, LocalDateTime occurredAt, String shardId) {
        return new ProcessStatusChanged(processId, businessKey, "a process", "order-fulfilment", 1, status, completion,
                T0, T0, "COMPLETED".equals(status) ? occurredAt : null, occurredAt, shardId);
    }
}
