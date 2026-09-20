package io.mateu.workflow.processindex;

import static org.assertj.core.api.Assertions.assertThat;

import io.mateu.workflow.application.readmodel.MessageSubscription;
import io.mateu.workflow.schema.ManagedSchema;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The subscription routing store against a real H2 (the guarded upsert path; PostgreSQL takes the
 * {@code ON CONFLICT} one), on the shipped V4 migration.
 */
class JdbcMessageSubscriptionStoreTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 20, 10, 0);

    private JdbcMessageSubscriptionStore store;

    @BeforeEach
    void freshDatabase() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:subs-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        new ManagedSchema("process-index", "classpath:db/migration/processindex",
                "eventconductor_process_index_history").migrate(dataSource);
        store = new JdbcMessageSubscriptionStore(dataSource);
    }

    private static MessageSubscription sub(String stepId, String name, String key, String shard) {
        return new MessageSubscription(stepId, name, key, shard, T0);
    }

    @Test
    void a_subscription_makes_its_shard_the_routing_answer() {
        store.subscribe(sub("se-1", "orderPaid", "bk-1", "shard-a"));

        assertThat(store.shardsWaitingFor("orderPaid", "bk-1")).containsExactly("shard-a");
        assertThat(store.shardsWaitingFor("orderPaid", "other")).isEmpty();
    }

    @Test
    void two_shards_waiting_for_the_same_key_both_come_back() {
        store.subscribe(sub("se-1", "orderPaid", "bk-1", "shard-a"));
        store.subscribe(sub("se-2", "orderPaid", "bk-1", "shard-b"));

        assertThat(store.shardsWaitingFor("orderPaid", "bk-1"))
                .containsExactlyInAnyOrder("shard-a", "shard-b");
    }

    @Test
    void re_subscribing_the_same_step_is_idempotent() {
        store.subscribe(sub("se-1", "orderPaid", "bk-1", "shard-a"));
        store.subscribe(sub("se-1", "orderPaid", "bk-1", "shard-a"));

        assertThat(store.shardsWaitingFor("orderPaid", "bk-1")).containsExactly("shard-a");
    }

    @Test
    void a_changed_key_moves_the_subscription() {
        store.subscribe(sub("se-1", "orderPaid", "old", "shard-a"));
        store.subscribe(sub("se-1", "orderPaid", "new", "shard-a"));

        assertThat(store.shardsWaitingFor("orderPaid", "old")).isEmpty();
        assertThat(store.shardsWaitingFor("orderPaid", "new")).containsExactly("shard-a");
    }

    @Test
    void unsubscribing_removes_the_routing_answer() {
        store.subscribe(sub("se-1", "orderPaid", "bk-1", "shard-a"));
        store.unsubscribe("se-1");

        assertThat(store.shardsWaitingFor("orderPaid", "bk-1")).isEmpty();
    }
}
