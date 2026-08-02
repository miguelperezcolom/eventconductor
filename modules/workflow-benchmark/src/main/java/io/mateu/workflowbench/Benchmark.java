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

        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                config.jdbcUrl(), config.jdbcUser(), config.jdbcPassword()));

        if ("install".equals(config.role())) {
            // Writes a definition into the engine's database and exits. Its own role because
            // swapping a definition under running processes is a scenario, not a side effect of
            // starting load.
            var resource = System.getProperty("bench.definition", "/workflows/bench-3-steps.json");
            System.out.println("installed workflow definition "
                    + io.mateu.workflowbench.soak.WorkflowInstaller.install(jdbc, resource)
                    + " from " + resource);
            return;
        }

        if (config.soaks()) {
            // A different question entirely — see SoakDriver. It shares the load path and nothing
            // else, and in particular it never asserts a duration or a rate: the verdict comes
            // from the verifier reading the database afterwards.
            try (var driver = new LoadDriver(config, true)) {
                new io.mateu.workflowbench.soak.SoakDriver(
                        jdbc, config.soakPrefix(), config.ratePerSecond(),
                        java.time.Duration.ofMinutes(config.soakMinutes()))
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
            awaitDefinition(jdbc);
            clearPreviousRun(jdbc);

            try (var driver = new LoadDriver(config)) {
                // Warm up: the first events pay for connection pools, Kafka metadata and JIT, and
                // folding that into the measurement makes the first percentile buckets
                // meaningless.
                var warmup = Math.min(200, config.processes());
                drive(driver, "warmup", warmup, config);
                awaitCompletion(jdbc, "warmup", warmup, 300);
                clearPreviousRun(jdbc);

                var commitsBefore = commits(jdbc);
                var start = System.nanoTime();
                drive(driver, "bench", config.processes(), config);
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
    private static void awaitDefinition(JdbcTemplate jdbc) throws InterruptedException {
        var deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            var found = jdbc.queryForObject(
                    "SELECT count(*) FROM workflow_definition_entity WHERE id = 'bench-3-steps'",
                    Integer.class);
            if (found != null && found > 0) {
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(
                "bench-3-steps is not deployed. A pod imports it from classpath:/workflows/ at "
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
