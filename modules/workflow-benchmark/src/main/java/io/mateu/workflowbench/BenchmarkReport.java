package io.mateu.workflowbench;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Turns a finished run into numbers that can be defended.
 *
 * <p>The one that matters is <b>inter-step latency</b>: the gap between a step finishing and the
 * next one starting. That window contains only engine work — writing the transition, publishing
 * it, routing it, picking it up, dispatching the next task — and no worker time at all. It is
 * the honest answer to "what does this engine cost per transition", and unlike throughput it does
 * not move when you make the workers faster or slower.
 *
 * <p>Throughput is reported too, and deliberately second. In any real deployment it is bounded by
 * what the workers can get through, not by the engine, so a throughput figure mostly describes
 * the load generator. Quoting it as an engine capability is the mistake this harness exists to
 * stop making.
 */
record BenchmarkReport(
        int processes, double wallClockSeconds,
        double p50GapMillis, double p95GapMillis, double p99GapMillis, double maxGapMillis,
        long transitions, long commits, double workerBusySeconds) {

    static BenchmarkReport collect(JdbcTemplate jdbc, BenchmarkConfig config,
                                   double wallClockSeconds, long commits) {
        var gaps = jdbc.queryForMap("""
                WITH ordered AS (
                    SELECT process_id,
                           started_at,
                           LAG(finished_at) OVER (PARTITION BY process_id ORDER BY _order) AS previous_finished
                    FROM step_execution_entity
                    WHERE process_id IN (SELECT id FROM process_entity WHERE business_key LIKE 'bench-%')
                ),
                gap AS (
                    SELECT EXTRACT(EPOCH FROM (started_at - previous_finished)) * 1000 AS ms
                    FROM ordered
                    WHERE previous_finished IS NOT NULL AND started_at IS NOT NULL
                )
                SELECT count(*) AS transitions,
                       coalesce(percentile_cont(0.50) WITHIN GROUP (ORDER BY ms), 0) AS p50,
                       coalesce(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms), 0) AS p95,
                       coalesce(percentile_cont(0.99) WITHIN GROUP (ORDER BY ms), 0) AS p99,
                       coalesce(max(ms), 0) AS worst
                FROM gap
                """);
        return new BenchmarkReport(
                config.processes(), wallClockSeconds,
                ((Number) gaps.get("p50")).doubleValue(),
                ((Number) gaps.get("p95")).doubleValue(),
                ((Number) gaps.get("p99")).doubleValue(),
                ((Number) gaps.get("worst")).doubleValue(),
                ((Number) gaps.get("transitions")).longValue(),
                commits,
                config.processes() * 3 * config.workerThinkMillis() / 1000.0);
    }

    String render(BenchmarkConfig config) {
        var steps = processes * 3L;
        var text = new StringBuilder("\n=== EventConductor benchmark ===\n")
                .append(config.describe()).append("\n\n")
                .append(config.ratePerSecond() == 0
                        ? "TRANSITION LATENCY UNDER SATURATION — mostly queueing, NOT engine cost\n"
                        : "ENGINE COST PER TRANSITION (step finished -> next step started; no worker time in it)\n")
                .append(String.format("  p50 %8.1f ms%n  p95 %8.1f ms%n  p99 %8.1f ms%n  max %8.1f ms%n",
                        p50GapMillis, p95GapMillis, p99GapMillis, maxGapMillis))
                .append(String.format("  over %d transitions%n%n", transitions))
                .append("THROUGHPUT (bounded by the workers, not the engine — read the caveats)\n")
                .append(String.format("  %d processes, %d steps in %.1f s -> %.1f process instances/s, %.1f steps/s%n",
                        processes, steps, wallClockSeconds, processes / wallClockSeconds, steps / wallClockSeconds))
                .append(String.format("  worker time in the run: %.1f s of simulated work across %d executions%n%n",
                        workerBusySeconds, steps))
                .append("DATABASE\n")
                .append(String.format("  %d commits -> %.0f/s, %.2f per step"
                                + "  (xact_commit counts implicit transactions: chattiness, not writes)%n%n",
                        commits, commits / wallClockSeconds, commits / (double) steps));
        if (config.ratePerSecond() == 0) {
            text.append("""
                    CAVEAT: this run was unpaced, so the pipeline saturated and the latencies above
                    are dominated by queueing behind the backlog. Read the throughput figure from
                    this run and the latency figure from a paced one (-Dbench.rate=N, below
                    whatever throughput came out here).
                    """);
        }
        if (config.everythingIsLocal()) {
            text.append("""
                    CAVEAT: this ran with -Dbench.role=all, so the pods, the worker and the load
                    generator were all in this JVM, and unless you pointed the harness elsewhere
                    the broker and the database are on this machine too. A number measured that
                    way describes the machine. It is fine for comparing one build against another
                    on the same box, which is what it is usually for, and it is not a scalability
                    claim. For that, split the roles across hosts — see "Across hosts" in the
                    README — and publish the tuning line above alongside the figure.
                    """);
        }
        return text.toString();
    }
}
