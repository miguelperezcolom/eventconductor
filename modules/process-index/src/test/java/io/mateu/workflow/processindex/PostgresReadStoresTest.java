package io.mateu.workflow.processindex;

import io.mateu.workflow.application.readmodel.MessageSubscription;
import io.mateu.workflow.application.readmodel.ProcessIndexRow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read stores on PostgreSQL, where they take their single-statement paths ({@code ON CONFLICT}):
 * the same contracts the H2 tests pin on the portable paths — the staleness guard, one placement per
 * key, an idempotent subscription — hold on the statements production actually runs.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresReadStoresTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 18, 10, 0);

    static PostgreSQLContainer<?> postgres;
    static DataSource dataSource;

    @BeforeAll
    static void startPostgres() throws Exception {
        // The data directory in memory: nothing to clean up, and no dependence on the Docker disk.
        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withTmpFs(java.util.Map.of("/var/lib/postgresql/data", "rw"));
        postgres.start();
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        // The shipped migrations, in order.
        var scripts = new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/processindex/V*.sql");
        Arrays.sort(scripts, Comparator.comparingInt(r -> Integer.parseInt(
                r.getFilename().substring(1, r.getFilename().indexOf("__")))));
        new ResourceDatabasePopulator(scripts).execute(dataSource);
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void emptyTables() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("TRUNCATE process_index, process_placement, message_subscription");
        }
    }

    private static ProcessIndexRow row(String id, String key, String status, LocalDateTime created,
                                       LocalDateTime updatedAt) {
        return new ProcessIndexRow(id, key, "Order " + key, "orders", 2, status, 50, created, created, null,
                updatedAt, "s1");
    }

    @Test
    void theAtomicUpsertInsertsUpdatesAndRejectsAStaleEvent() {
        var store = new JdbcProcessIndexStore(dataSource);
        store.upsert(row("p1", "order-1", "RUNNING", T0, T0));
        assertThat(store.findByProcessId("p1").orElseThrow()).isEqualTo(row("p1", "order-1", "RUNNING", T0, T0));

        store.upsert(row("p1", "order-1", "COMPLETED", T0, T0.plusMinutes(1)));
        // A redelivery of the older event, arriving late: it updates nothing.
        store.upsert(row("p1", "order-1", "RUNNING", T0, T0));
        assertThat(store.findByProcessId("p1").orElseThrow().status()).isEqualTo("COMPLETED");

        // The same event twice: still one row, same state.
        store.upsert(row("p1", "order-1", "COMPLETED", T0, T0.plusMinutes(1)));
        assertThat(store.countByStatus()).containsExactly(java.util.Map.entry("COMPLETED", 1L));
    }

    @Test
    void anEventWithoutAStampAlwaysWinsOnTheAtomicPath() {
        var store = new JdbcProcessIndexStore(dataSource);
        store.upsert(row("p1", "order-1", "RUNNING", T0, T0.plusMinutes(5)));
        store.upsert(row("p1", "order-1", "ERROR", T0, null));
        assertThat(store.findByProcessId("p1").orElseThrow().status()).isEqualTo("ERROR");
    }

    @Test
    void theListingIsPagedAndFilteredOnPostgres() {
        var store = new JdbcProcessIndexStore(dataSource);
        for (var i = 0; i < 5; i++) {
            store.upsert(row("p" + i, "order-" + i, i == 3 ? "ERROR" : "RUNNING", T0.plusMinutes(i), T0));
        }
        var page = store.search(null, false, 0, 2).orElseThrow();
        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.content()).extracting(ProcessIndexRow::processId).containsExactly("p4", "p3");

        assertThat(store.search("ORDER-2", false, 0, 10).orElseThrow().content())
                .extracting(ProcessIndexRow::processId).containsExactly("p2");
        assertThat(store.search(null, true, 0, 10).orElseThrow().content())
                .extracting(ProcessIndexRow::processId).containsExactly("p3");
        assertThat(store.findByBusinessKey("order-1").orElseThrow().processId()).isEqualTo("p1");
        assertThat(store.findByWorkflowDefinitionIdAndStatusIn("orders", List.of("ERROR")))
                .extracting(ProcessIndexRow::processId).containsExactly("p3");
    }

    @Test
    void theSingleStatementClaimKeepsTheIncumbentPlacement() throws Exception {
        var store = new JdbcProcessPlacementStore(dataSource);
        assertThat(store.claim("order-4711", "s0")).isEqualTo("s0");
        assertThat(store.claim("order-4711", "s1")).isEqualTo("s0");
        assertThat(store.find("order-4711")).contains("s0");
        assertThat(store.find("unknown")).isEmpty();

        // Concurrent claims of one fresh key, each with its own candidate: all agree on one shard.
        var threads = 8;
        var barrier = new CyclicBarrier(threads);
        var pool = Executors.newFixedThreadPool(threads);
        try {
            var claims = IntStream.range(0, threads)
                    .mapToObj(i -> (Callable<String>) () -> {
                        barrier.await();
                        return store.claim("order-race", "s" + i);
                    }).toList();
            var answers = pool.invokeAll(claims).stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).distinct().toList();
            assertThat(answers).hasSize(1);
            assertThat(store.find("order-race")).contains(answers.get(0));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theSubscriptionUpsertIsIdempotentAndMovesAChangedKey() {
        var store = new JdbcMessageSubscriptionStore(dataSource);
        store.subscribe(new MessageSubscription("step-1", "paid", "order-1", "s0", T0));
        store.subscribe(new MessageSubscription("step-1", "paid", "order-1", "s0", T0));
        assertThat(store.shardsWaitingFor("paid", "order-1")).containsExactly("s0");

        store.subscribe(new MessageSubscription("step-1", "paid", "order-2", "s0", T0.plusMinutes(1)));
        assertThat(store.shardsWaitingFor("paid", "order-1")).isEmpty();
        assertThat(store.shardsWaitingFor("paid", "order-2")).containsExactly("s0");

        store.unsubscribe("step-1");
        assertThat(store.shardsWaitingFor("paid", "order-2")).isEmpty();
    }
}
