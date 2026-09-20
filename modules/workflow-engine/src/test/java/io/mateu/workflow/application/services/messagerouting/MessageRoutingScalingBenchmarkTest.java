package io.mateu.workflow.application.services.messagerouting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.mateu.workflow.application.out.IngressPublisher;
import io.mateu.workflow.application.out.MessagePublisher;
import io.mateu.workflow.application.out.MessageSubscriptionRepository;
import io.mateu.workflow.application.out.ProcessPlacementRepository;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.processindex.JdbcProcessPlacementStore;
import io.mateu.workflow.schema.ManagedSchema;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Measures the number one thing sharded message routing exists to change: how many message
 * correlation queries each shard runs, as a function of the shard count.
 *
 * <p>Without routing a message is broadcast to the shared {@code messages} topic, so <b>every</b>
 * shard runs a correlation query for it — S×M queries for M messages over S shards, and the per-shard
 * cost stays at the full message rate no matter how many shards are added, which is the ceiling on
 * horizontal scaling. With routing on, a business-key message goes only to the shard that owns the
 * key (placement, layer 1), so the total is M and the per-shard cost is M/S — it drops as shards are
 * added. This test drives real messages through the real {@link MessageRouter} and the real placement
 * store (H2) and records the per-shard counts; it is the source of the numbers in
 * {@code guides/performance.md}. It is not a wall-clock throughput run (that needs a broker and a
 * message workload in the benchmark harness); it measures the database work each shard is asked to do.
 */
class MessageRoutingScalingBenchmarkTest {

    private static final int MESSAGES = 100_000;

    private DataSource h2() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:routing-bench-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        new ManagedSchema("process-index", "classpath:db/migration/processindex",
                "eventconductor_process_index_history").migrate(dataSource);
        return dataSource;
    }

    /** Correlation queries per shard for M business-key messages over {@code shards} shards, routing on. */
    private long[] perShardWithRouting(int shards) {
        var placement = new JdbcProcessPlacementStore(h2());
        // Place M business keys round-robin across the shards, as the ingress router would.
        for (int i = 0; i < MESSAGES; i++) {
            placement.claim("bk-" + i, "shard-" + (i % shards));
        }

        var perShard = new ConcurrentHashMap<String, LongAdder>();
        IngressPublisher ingress = (event, shardId) ->
                perShard.computeIfAbsent(shardId, s -> new LongAdder()).increment();
        MessagePublisher broadcast = message -> {
            throw new AssertionError("a placed business-key message must not broadcast");
        };

        var classifier = mock(MessageClassifier.class);
        when(classifier.classify("m")).thenReturn(MessageClassification.BUSINESS_KEY);
        var router = new MessageRouter(classifier, provider(placement), providerNull(),
                ingress, broadcast, mock(MessageRoutingMetrics.class), true);

        for (int i = 0; i < MESSAGES; i++) {
            router.route(new MessageReceived("m", "bk-" + i, List.of()));
        }

        var counts = new long[shards];
        perShard.forEach((shard, adder) -> counts[Integer.parseInt(shard.substring("shard-".length()))] = adder.sum());
        return counts;
    }

    @Test
    void per_shard_correlation_queries_drop_as_shards_are_added_with_routing() {
        var report = new StringBuilder("\n=== Sharded message routing: correlation queries per shard (M="
                + MESSAGES + " business-key messages) ===\n");
        report.append(String.format("%-8s | %-22s | %-22s%n", "shards", "broadcast (routing off)", "routed (routing on)"));

        long previousRoutedMax = Long.MAX_VALUE;
        for (int shards : new int[] {2, 4, 8}) {
            var routed = perShardWithRouting(shards);
            long routedMax = 0;
            long routedTotal = 0;
            for (long c : routed) {
                routedMax = Math.max(routedMax, c);
                routedTotal += c;
            }
            // Broadcast: every shard runs a query for every message.
            long broadcastPerShard = MESSAGES;

            report.append(String.format("%-8d | %-22s | %-22s%n", shards,
                    broadcastPerShard + "/shard (Σ" + ((long) broadcastPerShard * shards) + ")",
                    "~" + routedMax + "/shard (Σ" + routedTotal + ")"));

            // Routing sends each message to exactly one shard: the total stays at M...
            assertThat(routedTotal).isEqualTo(MESSAGES);
            // ...and the busiest shard's load falls as shards are added (horizontal scaling)...
            assertThat(routedMax).isLessThan(previousRoutedMax);
            // ...and is far below the broadcast per-shard cost.
            assertThat(routedMax).isLessThan(broadcastPerShard);
            previousRoutedMax = routedMax;
        }
        System.out.println(report);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ProcessPlacementRepository> provider(ProcessPlacementRepository store) {
        ObjectProvider<ProcessPlacementRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MessageSubscriptionRepository> providerNull() {
        ObjectProvider<MessageSubscriptionRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }
}
