package io.mateu.workflowdist;

import io.mateu.workflow.application.sync.SyncInvocationService;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Latency of a synchronous invocation — request to reply — with the fast path on and off, over real
 * PostgreSQL and Kafka (one orchestrator pod, kafka + jpa mode, one Kafka worker).
 *
 * <p><b>A benchmark, not a test</b>: opt in with {@code -Dbench.sync=true}. It runs invocations one
 * after another (no queueing, so the number is the path's cost, not a backlog's depth) for two
 * definitions — engine-internal steps only (the fast path can take the whole process) and two
 * Kafka-worker steps (it only covers the stretches between workers, D3) — and prints p50/p95/p99.
 * Same caveats as {@code modules/workflow-benchmark}: one machine, everything in containers; read the
 * ratio between the two runs, not the absolute numbers.
 */
class SyncLatencyBenchmarkTest extends AbstractDistTest {

    private static final int WARMUP = 30;
    private static final int RUNS = Integer.getInteger("bench.sync.runs", 200);

    @Test
    void fastPathAgainstTheNormalPath() {
        Assumptions.assumeTrue(Boolean.getBoolean("bench.sync"), "opt-in benchmark: -Dbench.sync=true");
        DistInfra.ensureWorkerStarted();
        var report = new StringBuilder("\n=== SYNC INVOCATION LATENCY (request -> reply), " + RUNS
                + " sequential invocations, kafka + jpa, " + DistInfra.tuning() + " ===\n");
        for (var pods : List.of(1, 2)) {
            for (var inline : List.of(false, true)) {
                var contexts = new ArrayList<ConfigurableApplicationContext>();
                for (int p = 0; p < pods; p++) {
                    contexts.add(DistInfra.startOrchestrator(Map.of(
                            "workflow.sync.inline.enabled", String.valueOf(inline),
                            "workflow.sync.max-deadline-ms", "60000")));
                }
                try {
                    for (var definition : List.of("dist-sync-internal", "dist-sync-workers")) {
                        // Requests always land on the first pod; with two, the partition owner of a
                        // process is the other pod about half the time — the cross-pod handoff.
                        var samples = measure(contexts.getFirst(), definition);
                        report.append(String.format("  pods=%d %-20s inline=%-5s p50=%6.1f ms  p95=%6.1f ms  p99=%6.1f ms%n",
                                pods, definition, inline, pct(samples, 50), pct(samples, 95), pct(samples, 99)));
                    }
                } finally {
                    contexts.forEach(ConfigurableApplicationContext::close);
                }
            }
        }
        System.out.println(report);
    }

    private static List<Double> measure(ConfigurableApplicationContext pod, String definition) {
        var service = pod.getBean(SyncInvocationService.class);
        var samples = new ArrayList<Double>();
        for (int i = 0; i < WARMUP + RUNS; i++) {
            var key = UUID.randomUUID().toString();
            var started = System.nanoTime();
            var begun = service.start(new SyncInvocationService.StartRequest(definition, key, null,
                    Map.of("orderId", key), Duration.ofSeconds(30), null));
            var view = service.await(begun.invocation(), begun.timeToWait()).join();
            var millis = (System.nanoTime() - started) / 1_000_000.0;
            assertThat(view.replied()).as("reply within the deadline").isTrue();
            if (i >= WARMUP) {
                samples.add(millis);
            }
        }
        return samples;
    }

    private static double pct(List<Double> samples, int percentile) {
        var sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        var index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
