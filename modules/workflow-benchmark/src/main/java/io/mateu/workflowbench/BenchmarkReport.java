package io.mateu.workflowbench;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Turns a finished run into numbers that can be defended.
 *
 * <p>Both figures here are per <b>transition</b> — one step advanced by the engine, which is the
 * engine's actual unit of work: an outbox write, a relay, a dispatch, a reply, a status change
 * consumed to decide what comes next. Everything the engine does, it does once per transition.
 *
 * <p><b>Cost per transition</b> is the gap between a step finishing and the next one starting.
 * That window contains only engine work and no worker time at all, so it does not move when you
 * make the workers faster or slower.
 *
 * <p><b>Transitions per second</b> is the same unit read the other way. Process instances per
 * second is reported too, and deliberately last, because it is not a property of the engine: a
 * three-step definition and a twelve-step saga differ fourfold in it with nothing about the engine
 * having changed, and a definition that waits on a human or a timer makes it meaningless — a
 * process can be in flight for three days and cost the engine two transitions. Whenever it is
 * quoted, the steps-per-process it was measured at has to be quoted beside it.
 */
record BenchmarkReport(
        double wallClockSeconds,
        double p50GapMillis, double p95GapMillis, double p99GapMillis, double maxGapMillis,
        long stepExecutions, long processesSeen, long transitions,
        long commits, double workerBusySeconds) {

    static BenchmarkReport collect(JdbcTemplate jdbc, BenchmarkConfig config,
                                   double wallClockSeconds, long commits) {
        var measured = jdbc.queryForMap("""
                WITH step AS (
                    SELECT process_id, started_at, finished_at, _order
                    FROM step_execution_entity
                    WHERE process_id IN (SELECT id FROM process_entity WHERE business_key LIKE 'bench-%')
                ),
                ordered AS (
                    SELECT process_id,
                           started_at,
                           LAG(finished_at) OVER (PARTITION BY process_id ORDER BY _order) AS previous_finished
                    FROM step
                ),
                gap AS (
                    SELECT EXTRACT(EPOCH FROM (started_at - previous_finished)) * 1000 AS ms
                    FROM ordered
                    WHERE previous_finished IS NOT NULL AND started_at IS NOT NULL
                )
                SELECT (SELECT count(*) FROM step) AS step_executions,
                       (SELECT count(DISTINCT process_id) FROM step) AS processes_seen,
                       count(*) AS transitions,
                       coalesce(percentile_cont(0.50) WITHIN GROUP (ORDER BY ms), 0) AS p50,
                       coalesce(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms), 0) AS p95,
                       coalesce(percentile_cont(0.99) WITHIN GROUP (ORDER BY ms), 0) AS p99,
                       coalesce(max(ms), 0) AS worst
                FROM gap
                """);
        var stepExecutions = ((Number) measured.get("step_executions")).longValue();
        return new BenchmarkReport(
                wallClockSeconds,
                ((Number) measured.get("p50")).doubleValue(),
                ((Number) measured.get("p95")).doubleValue(),
                ((Number) measured.get("p99")).doubleValue(),
                ((Number) measured.get("worst")).doubleValue(),
                stepExecutions,
                ((Number) measured.get("processes_seen")).longValue(),
                ((Number) measured.get("transitions")).longValue(),
                commits,
                // Counted, never assumed: the steps per process are whatever the definitions the
                // run drove actually have, which is the whole reason the headline is not PI/s.
                stepExecutions * config.workerThinkMillis() / 1000.0);
    }

    String render(BenchmarkConfig config) {
        var stepsPerProcess = processesSeen == 0 ? 0 : stepExecutions / (double) processesSeen;
        var commitsPerTransition = stepExecutions == 0 ? 0 : commits / (double) stepExecutions;
        var text = new StringBuilder("\n=== EventConductor benchmark ===\n")
                .append(config.describe()).append("\n\n")
                .append(config.ratePerSecond() == 0
                        ? "TRANSITION LATENCY UNDER SATURATION — mostly queueing, NOT engine cost\n"
                        : "ENGINE COST PER TRANSITION (step finished -> next step started; no worker time in it)\n")
                .append(String.format("  p50 %8.1f ms%n  p95 %8.1f ms%n  p99 %8.1f ms%n  max %8.1f ms%n",
                        p50GapMillis, p95GapMillis, p99GapMillis, maxGapMillis))
                // Fewer samples than transitions, by one per process: the first step of a process
                // has no predecessor to measure the gap from. The rate below counts all of them.
                .append(String.format("  over %d measured transitions%n%n", transitions))
                .append("THROUGHPUT IN TRANSITIONS/S (the engine's unit of work: one step advanced)\n")
                .append(String.format("  %d transitions in %.1f s -> %.1f transitions/s%n",
                        stepExecutions, wallClockSeconds, stepExecutions / wallClockSeconds))
                .append(String.format("  bounded by the workers, not the engine — read the caveats%n%n"))
                .append("PROCESS INSTANCES/S (not an engine property — quote it only with the line below)\n")
                .append(String.format("  %d processes in %.1f s -> %.1f process instances/s,"
                                + " at %.1f steps per process%n",
                        processesSeen, wallClockSeconds, processesSeen / wallClockSeconds, stepsPerProcess))
                .append("  Same engine, a definition with twice the steps: half the PI/s. A definition\n")
                .append("  that waits on a human or a timer: no meaningful figure at all.\n")
                .append(String.format("  worker time in the run: %.1f s of simulated work across %d executions%n%n",
                        workerBusySeconds, stepExecutions))
                .append("DATABASE\n")
                .append(String.format("  %d commits -> %.0f/s, %.2f per transition"
                                + "  (xact_commit counts implicit transactions: chattiness, not writes)%n%n",
                        commits, commits / wallClockSeconds, commitsPerTransition));
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
