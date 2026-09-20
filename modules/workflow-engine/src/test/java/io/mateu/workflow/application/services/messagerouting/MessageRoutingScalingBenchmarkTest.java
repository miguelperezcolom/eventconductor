package io.mateu.workflow.application.services.messagerouting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.mateu.workflow.application.out.IngressPublisher;
import io.mateu.workflow.application.out.MessagePublisher;
import io.mateu.workflow.application.out.MessageSubscriptionRepository;
import io.mateu.workflow.application.out.ProcessPlacementRepository;
import io.mateu.workflow.application.readmodel.MessageSubscription;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.processindex.JdbcMessageSubscriptionStore;
import io.mateu.workflow.processindex.JdbcProcessPlacementStore;
import io.mateu.workflow.schema.ManagedSchema;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

    /** Correlation queries per shard for M expression-key messages resolved by the subscription table. */
    private long[] perShardWithSubscriptionRouting(int shards) {
        var subscriptions = new JdbcMessageSubscriptionStore(h2());
        // One waiting step per key, placed round-robin across the shards, as the projection would.
        for (int i = 0; i < MESSAGES; i++) {
            subscriptions.subscribe(new MessageSubscription(
                    "se-" + i, "m", "k-" + i, "shard-" + (i % shards), LocalDateTime.now()));
        }

        var perShard = new ConcurrentHashMap<String, LongAdder>();
        IngressPublisher ingress = (event, shardId) ->
                perShard.computeIfAbsent(shardId, s -> new LongAdder()).increment();
        MessagePublisher broadcast = message -> {
            throw new AssertionError("a subscribed expression-key message must not broadcast");
        };

        var classifier = mock(MessageClassifier.class);
        // An expression key skips placement (layer 1) and is resolved by the subscription table (layer 2).
        when(classifier.classify("m")).thenReturn(MessageClassification.EXPRESSION);
        var router = new MessageRouter(classifier, provider(null), providerSub(subscriptions),
                ingress, broadcast, mock(MessageRoutingMetrics.class), true);

        for (int i = 0; i < MESSAGES; i++) {
            router.route(new MessageReceived("m", "k-" + i, List.of()));
        }

        var counts = new long[shards];
        perShard.forEach((shard, adder) -> counts[Integer.parseInt(shard.substring("shard-".length()))] = adder.sum());
        return counts;
    }

    @Test
    void per_shard_correlation_queries_drop_as_shards_are_added_with_subscription_routing() {
        var report = new StringBuilder("\n=== Sharded message routing, layer 2 (subscription table): "
                + "correlation queries per shard (M=" + MESSAGES + " expression-key messages) ===\n");
        report.append(String.format("%-8s | %-22s | %-24s%n", "shards", "broadcast (routing off)", "routed via subscriptions"));

        long previousRoutedMax = Long.MAX_VALUE;
        for (int shards : new int[] {2, 4, 8}) {
            var routed = perShardWithSubscriptionRouting(shards);
            long routedMax = 0;
            long routedTotal = 0;
            for (long c : routed) {
                routedMax = Math.max(routedMax, c);
                routedTotal += c;
            }
            report.append(String.format("%-8d | %-22s | %-24s%n", shards,
                    MESSAGES + "/shard (Σ" + ((long) MESSAGES * shards) + ")",
                    "~" + routedMax + "/shard (Σ" + routedTotal + ")"));

            assertThat(routedTotal).isEqualTo(MESSAGES);         // each message reaches exactly one shard
            assertThat(routedMax).isLessThan(previousRoutedMax); // per-shard load falls as shards are added
            assertThat(routedMax).isLessThan((long) MESSAGES);   // and is far below the broadcast per-shard cost
            previousRoutedMax = routedMax;
        }
        System.out.println(report);
    }

    /**
     * Correlation queries per shard for M messages <b>broadcast</b> (routing off) but filtered on the
     * receiving side by the per-shard Bloom filter (layer 3). Each shard's filter holds only its own
     * share of the waiting keys, so a broadcast message it cannot match is dropped without a query.
     */
    private long[] perShardBroadcastWithBloom(int shards) {
        // Build each shard's filter from the keys whose one waiting step lives on that shard.
        var byShard = new ArrayList<List<String[]>>();
        for (int s = 0; s < shards; s++) {
            byShard.add(new ArrayList<>());
        }
        for (int i = 0; i < MESSAGES; i++) {
            byShard.get(i % shards).add(new String[] {"m", "k-" + i});
        }
        var filters = new WaitingMessageFilter[shards];
        for (int s = 0; s < shards; s++) {
            filters[s] = new WaitingMessageFilter(true, MESSAGES, 0.01);
            filters[s].rebuild(byShard.get(s));
        }

        // Every message is broadcast to every shard; a shard runs the query only when its filter
        // says the pair might be waiting there (its own key, or a rare false positive).
        var queries = new long[shards];
        for (int i = 0; i < MESSAGES; i++) {
            for (int s = 0; s < shards; s++) {
                if (filters[s].mightBeWaitingFor("m", "k-" + i)) {
                    queries[s]++;
                }
            }
        }
        return queries;
    }

    @Test
    void per_shard_correlation_queries_drop_with_the_bloom_filter_even_on_broadcast() {
        var report = new StringBuilder("\n=== Sharded message routing, layer 3 (per-shard Bloom filter): "
                + "correlation queries per shard (M=" + MESSAGES + " broadcast messages) ===\n");
        report.append(String.format("%-8s | %-22s | %-26s%n", "shards", "broadcast (no filter)", "broadcast + filter"));

        long previousMax = Long.MAX_VALUE;
        for (int shards : new int[] {2, 4, 8}) {
            var queried = perShardBroadcastWithBloom(shards);
            long max = 0;
            long total = 0;
            for (long c : queried) {
                max = Math.max(max, c);
                total += c;
            }
            report.append(String.format("%-8d | %-22s | %-26s%n", shards,
                    MESSAGES + "/shard (Σ" + ((long) MESSAGES * shards) + ")",
                    "~" + max + "/shard (Σ" + total + ")"));

            long ownShare = MESSAGES / shards;
            // Every real waiter is still queried (no false negatives): at least its own share.
            assertThat(max).isGreaterThanOrEqualTo(ownShare);
            // The per-shard load falls as shards are added, and stays well under the broadcast cost —
            // the false-positive overhead is a small fraction on top of the M/S own share.
            assertThat(max).isLessThan(previousMax);
            assertThat(max).isLessThan((long) MESSAGES);
            assertThat(max).isLessThan(ownShare + MESSAGES / 20); // < own share + ~5% (fpp headroom)
            previousMax = max;
        }
        System.out.println(report);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MessageSubscriptionRepository> providerSub(MessageSubscriptionRepository store) {
        ObjectProvider<MessageSubscriptionRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        return provider;
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
