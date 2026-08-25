package io.mateu.workflow.processindex;

import io.mateu.workflow.schema.ManagedSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The claim is the fleet's idempotency guarantee, so what is tested is the guarantee rather than the
 * SQL: one key is placed once, everybody who asks gets that same answer, and a claim that cannot be
 * made fails loudly instead of inventing a placement.
 */
class JdbcProcessPlacementStoreTest {

    private DataSource dataSource;
    private JdbcProcessPlacementStore store;

    @BeforeEach
    void freshDatabase() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:placement-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        new ManagedSchema("process-index", "classpath:db/migration/processindex",
                "eventconductor_process_index_history").migrate(dataSource);
        store = new JdbcProcessPlacementStore(dataSource);
    }

    @Test
    void theFirstClaimWinsTheKey() {
        assertThat(store.claim("order-4711", "s0")).isEqualTo("s0");
    }

    @Test
    void everyLaterClaimGetsTheIncumbentShardHoweverItWasRoundRobined() {
        store.claim("order-4711", "s0");

        // A redelivery of the same creation, round-robined to a different candidate. The candidate
        // loses: the key was already placed, and that placement is not negotiable.
        assertThat(store.claim("order-4711", "s1")).isEqualTo("s0");
        assertThat(store.claim("order-4711", "s2")).isEqualTo("s0");
    }

    @Test
    void differentKeysAreSpreadAcrossTheCandidatesTheyWereOffered() {
        assertThat(store.claim("k1", "s0")).isEqualTo("s0");
        assertThat(store.claim("k2", "s1")).isEqualTo("s1");
        assertThat(store.claim("k3", "s0")).isEqualTo("s0");
    }

    @Test
    void concurrentClaimsOfOneKeyAllAgreeOnOneShard() throws Exception {
        var contenders = 16;
        var barrier = new CyclicBarrier(contenders);
        try (var pool = Executors.newFixedThreadPool(contenders)) {
            List<Callable<String>> claims = IntStream.range(0, contenders)
                    .<Callable<String>>mapToObj(i -> () -> {
                        barrier.await();
                        return store.claim("order-4711", "s" + i);
                    })
                    .toList();

            var answers = pool.invokeAll(claims).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).distinct().toList();

            // One answer, and it is one of the candidates that actually asked.
            assertThat(answers).hasSize(1);
            assertThat(answers.getFirst()).startsWith("s");
        }
    }

    @Test
    void aClaimAgainstAnUnreachableDatabaseFailsRatherThanGuessing() {
        var unreachable = new DriverManagerDataSource(
                "jdbc:h2:mem:gone;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        var broken = new JdbcProcessPlacementStore(unreachable);   // no schema: the table is missing

        // Fail closed. A creation is retryable at its source; a duplicated process is not repairable.
        assertThatThrownBy(() -> broken.claim("order-4711", "s0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("order-4711");
    }
}
