package io.mateu.workflowbench;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;

/**
 * Measures what the engine costs, against PostgreSQL and Kafka that you provide.
 *
 * <p>Deliberately not a test. Tests assert; this produces numbers, and numbers need conditions
 * printed beside them or they get quoted out of context — which is exactly what happened to the
 * figure in the comparison docs, measured on a laptop running everything at once and repeated as
 * though it said something about scale.
 *
 * <p>See README.md for how to run it and, more usefully, for what each number does and does not
 * support.
 */
public final class Benchmark {

    public static void main(String[] args) throws Exception {
        var config = BenchmarkConfig.fromSystemProperties();
        System.out.println("Starting benchmark: " + config.describe());

        // Per-shard databases (a single-element list — the plain jdbcUrl — when not sharded). The first
        // is where the soak driver writes its progress; the reconciler and installer span them all.
        var shardJdbcUrls = config.shardJdbcUrls();
        var jdbc = jdbcFor(shardJdbcUrls.get(0), config);

        if ("install".equals(config.role())) {
            // Writes a definition into the engine's database and exits. Its own role because
            // swapping a definition under running processes is a scenario, not a side effect of
            // starting load. Sharded: each shard creates from its own definitions, so install on all.
            var resource = System.getProperty("bench.definition", "/workflows/bench-3-steps.json");
            for (var url : shardJdbcUrls) {
                System.out.println("installed workflow definition "
                        + io.mateu.workflowbench.soak.WorkflowInstaller.install(jdbcFor(url, config), resource)
                        + " from " + resource + " on " + url);
            }
            return;
        }

        if ("verify".equals(config.role())) {
            // The zero-loss verdict, computed from the database after a run has drained. Its own
            // role, and an exit code, so an autonomous controller can gate on it. Fans out across shards.
            var shardJdbcs = shardJdbcUrls.stream().map(url -> jdbcFor(url, config)).toList();
            var verdict = io.mateu.workflowbench.soak.Reconciler.verifyAcrossShards(shardJdbcs, config.soakPrefix());
            // With a fleet database, also cross-check the standalone projector's read model and the
            // placement claim — reached by a different path from the shards' own tables, which is the
            // only reason the answer means anything. Kept as an ADDITION to the per-shard verdict: a
            // read model verified by reading the read model proves nothing.
            if (!config.fleetJdbcUrl().isBlank()) {
                var shardIds = config.shardList();
                var byId = new java.util.LinkedHashMap<String, org.springframework.jdbc.core.JdbcTemplate>();
                for (var i = 0; i < shardIds.size() && i < shardJdbcs.size(); i++) {
                    byId.put(shardIds.get(i), shardJdbcs.get(i));
                }
                verdict = io.mateu.workflowbench.soak.FleetIndexReconciler.merge(verdict,
                        io.mateu.workflowbench.soak.FleetIndexReconciler.verify(
                                jdbcFor(config.fleetJdbcUrl(), config), byId, config.soakPrefix()));
            }
            System.out.println(verdict.render());
            try {
                System.out.println("verdict-json " + new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(verdict));
            } catch (Exception ignored) {
                // The human render above is the verdict; JSON is a convenience for the controller.
            }
            System.exit(verdict.pass() ? 0 : 1);
        }

        if (config.soaks()) {
            // A different question entirely — see SoakDriver. It shares the load path and nothing
            // else, and in particular it never asserts a duration or a rate: the verdict comes
            // from the verifier reading the database afterwards.
            var workload = "scale".equals(config.workload())
                    ? new io.mateu.workflowbench.soak.ScaleWorkload(
                            config.sagaWeightPct(), config.compPermil(), config.compFailPermil())
                    : io.mateu.workflowbench.soak.Workload.linear();
            System.out.println("soak workload=" + config.workload());
            // The suite must exist on every shard — a shard creates from its own definitions, so one
            // missing there dead-letters every process placed on it. Progress still lives on shard 0.
            var installTargets = shardJdbcUrls.stream().map(url -> jdbcFor(url, config)).toList();
            try (var driver = new LoadDriver(config, true)) {
                new io.mateu.workflowbench.soak.SoakDriver(
                        jdbc, installTargets, config.soakPrefix(), config.ratePerSecond(),
                        java.time.Duration.ofMinutes(config.soakMinutes()), workload)
                        .run(driver);
            }
            return;
        }

        double wallClock;
        long commits;
        var contexts = new ArrayList<ConfigurableApplicationContext>();
        try {
            if (config.startsWorker()) {
                contexts.add(BenchmarkApps.startWorker(config));
            }
            for (var i = 0; config.startsPods() && i < config.pods(); i++) {
                contexts.add(BenchmarkApps.startOrchestrator(config, i));
            }
            if (!config.drivesLoad()) {
                // A pods-only or worker-only process exists to be driven by another one. It has
                // nothing to measure; it just has to stay up.
                System.out.println("Running as " + config.describe() + " — idle until stopped.");
                Thread.currentThread().join();
                return;
            }
            // The message workload drives a definition that parks on a WAIT_FOR_MESSAGE step, so the
            // run also measures the receiving-side, cross-shard message path (correlation, and — when
            // sharded — the per-shard filter that keeps a broadcast from costing every shard a query).
            var messageWorkload = "message".equals(config.workload());
            awaitDefinition(jdbc, messageWorkload ? "bench-wait-message" : "bench-3-steps");
            clearPreviousRun(jdbc);

            try (var driver = new LoadDriver(config)) {
                // Warm up: the first events pay for connection pools, Kafka metadata and JIT, and
                // folding that into the measurement makes the first percentile buckets
                // meaningless.
                var warmup = Math.min(200, config.processes());
                if (messageWorkload) {
                    driveMessages(driver, jdbc, "warmup", warmup, config);
                } else {
                    drive(driver, "warmup", warmup, config);
                }
                awaitCompletion(jdbc, "warmup", warmup, 300);
                clearPreviousRun(jdbc);

                var commitsBefore = commits(jdbc);
                var start = System.nanoTime();
                if (messageWorkload) {
                    driveMessages(driver, jdbc, "bench", config.processes(), config);
                } else {
                    drive(driver, "bench", config.processes(), config);
                }
                awaitCompletion(jdbc, "bench", config.processes(), 900);
                wallClock = (System.nanoTime() - start) / 1_000_000_000.0;
                commits = commits(jdbc) - commitsBefore;
            }

            System.out.println(BenchmarkReport.collect(jdbc, config, wallClock, commits).render(config));
        } finally {
            contexts.forEach(ConfigurableApplicationContext::close);
        }
    }

    /**
     * Waits for a pod to have imported the definition, asked of the database rather than of a
     * bean — the pods may be on another host entirely, and the database is the one thing every
     * role can see.
     */
    private static JdbcTemplate jdbcFor(String url, BenchmarkConfig config) {
        return new JdbcTemplate(new DriverManagerDataSource(url, config.jdbcUser(), config.jdbcPassword()));
    }

    private static void awaitDefinition(JdbcTemplate jdbc, String definitionId) throws InterruptedException {
        var deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            var found = jdbc.queryForObject(
                    "SELECT count(*) FROM workflow_definition_entity WHERE id = ?",
                    Integer.class, definitionId);
            if (found != null && found > 0) {
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(
                definitionId + " is not deployed. A pod imports it from classpath:/workflows/ at "
                        + "startup — start the pods before the driver, and check they see this database.");
    }

    private static void clearPreviousRun(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM step_execution_entity WHERE process_id IN "
                + "(SELECT id FROM process_entity WHERE business_key LIKE 'bench-%' OR business_key LIKE 'warmup-%')");
        jdbc.update("DELETE FROM process_entity WHERE business_key LIKE 'bench-%' OR business_key LIKE 'warmup-%'");
        jdbc.update("DELETE FROM outbox_message_entity WHERE status = 'Sent'");
    }

    /**
     * Publishes the load, paced when a rate is set.
     *
     * <p>Pacing is not politeness, it is what makes the latency number mean anything. An unpaced
     * burst saturates the pipeline immediately, and from then on the gap between one step and the
     * next is time spent queueing behind everything else — a measure of how deep the backlog got,
     * not of what the engine costs to move a process forward.
     */
    private static void drive(LoadDriver driver, String prefix, int count,
                              BenchmarkConfig config) throws InterruptedException {
        var nanosBetween = config.ratePerSecond() == 0 ? 0L : 1_000_000_000L / config.ratePerSecond();
        var next = System.nanoTime();
        for (var i = 0; i < count; i++) {
            driver.createProcess(prefix + "-" + i);
            if (nanosBetween > 0) {
                next += nanosBetween;
                var wait = next - System.nanoTime();
                if (wait > 0) {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                }
            }
        }
        driver.flush();
    }

    /**
     * Drives the message workload: create {@code count} processes that each run a step, park on a
     * {@code WAIT_FOR_MESSAGE}, then advance again once their message arrives.
     *
     * <p>Creation is paced like {@link #drive} so the arrival rate is still the experiment. The
     * resume messages are then sent in a burst <em>after</em> every process is confirmed waiting: the
     * point of this workload is the correlation and cross-shard delivery of the message, and pacing
     * those against processes that were not waiting yet would measure the wait, not the delivery. Each
     * process correlates the resume by its own business key, so the message reaches exactly the shard
     * holding it.
     */
    private static void driveMessages(LoadDriver driver, JdbcTemplate jdbc, String prefix, int count,
                                      BenchmarkConfig config) throws InterruptedException {
        var nanosBetween = config.ratePerSecond() == 0 ? 0L : 1_000_000_000L / config.ratePerSecond();
        var next = System.nanoTime();
        for (var i = 0; i < count; i++) {
            driver.createProcess("bench-wait-message", prefix + "-" + i, java.util.List.of(), null);
            if (nanosBetween > 0) {
                next += nanosBetween;
                var wait = next - System.nanoTime();
                if (wait > 0) {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                }
            }
        }
        driver.flush();

        awaitWaiting(jdbc, prefix, count, 300);

        for (var i = 0; i < count; i++) {
            driver.sendMessage("resume", prefix + "-" + i, null);
        }
        driver.flush();
    }

    /** Waits until every driven process has reached its {@code WAIT_FOR_MESSAGE} step (PENDING). */
    private static void awaitWaiting(JdbcTemplate jdbc, String prefix, int expected, int timeoutSeconds)
            throws InterruptedException {
        var deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            var waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM step_execution_entity se JOIN process_entity p ON se.process_id = p.id "
                            + "WHERE p.business_key LIKE ? AND se.step_id = 'wait' AND se.status = 'PENDING'",
                    Integer.class, prefix + "-%");
            if (waiting != null && waiting >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException(
                "Only some processes reached the wait step within " + timeoutSeconds
                        + "s — the message run is not comparable to any other");
    }

    private static void awaitCompletion(JdbcTemplate jdbc, String prefix, int expected, int timeoutSeconds)
            throws InterruptedException {
        var deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            var done = jdbc.queryForObject(
                    "SELECT count(*) FROM process_entity WHERE business_key LIKE ? AND status = 'COMPLETED'",
                    Integer.class, prefix + "-%");
            if (done != null && done >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException(
                "Only some processes completed within " + timeoutSeconds + "s — the run is not comparable to any other");
    }

    private static long commits(JdbcTemplate jdbc) {
        var count = jdbc.queryForObject(
                "SELECT xact_commit FROM pg_stat_database WHERE datname = current_database()", Long.class);
        return count == null ? 0 : count;
    }

    private Benchmark() {
    }
}
