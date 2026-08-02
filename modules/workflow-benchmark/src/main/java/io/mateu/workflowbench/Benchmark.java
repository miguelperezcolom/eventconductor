package io.mateu.workflowbench;

import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;

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

        var contexts = new ArrayList<ConfigurableApplicationContext>();
        try {
            contexts.add(BenchmarkApps.startWorker(config));
            for (var i = 0; i < config.pods(); i++) {
                contexts.add(BenchmarkApps.startOrchestrator(config, i));
            }
            var orchestrator = contexts.get(1);
            awaitDefinition(orchestrator);
            clearPreviousRun(jdbc);

            // Warm up: the first events pay for connection pools, Kafka metadata and JIT, and
            // folding that into the measurement makes the first percentile buckets meaningless.
            drive(orchestrator, "warmup", Math.min(200, config.processes()), config);
            awaitCompletion(jdbc, "warmup", Math.min(200, config.processes()), 120);
            clearPreviousRun(jdbc);

            var commitsBefore = commits(jdbc);
            var start = System.nanoTime();
            drive(orchestrator, "bench", config.processes(), config);
            awaitCompletion(jdbc, "bench", config.processes(), 900);
            var wallClock = (System.nanoTime() - start) / 1_000_000_000.0;

            System.out.println(BenchmarkReport
                    .collect(jdbc, config, wallClock, commits(jdbc) - commitsBefore)
                    .render(config));
        } finally {
            contexts.forEach(ConfigurableApplicationContext::close);
        }
    }

    /** The classpath importer runs as a startup runner, so give it a moment to have run. */
    private static void awaitDefinition(ConfigurableApplicationContext orchestrator) throws InterruptedException {
        var definitions = orchestrator.getBean(WorkflowDefinitionRepository.class);
        var deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (definitions.findById("bench-3-steps").isPresent()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException(
                "bench-3-steps was never deployed. The engine imports definitions from "
                        + "classpath:/workflows/ at startup; check src/main/resources/workflows.");
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
    private static void drive(ConfigurableApplicationContext orchestrator, String prefix, int count,
                              BenchmarkConfig config) throws InterruptedException {
        var publisher = orchestrator.getBean(UpstreamEventPublisher.class);
        var nanosBetween = config.ratePerSecond() == 0 ? 0L : 1_000_000_000L / config.ratePerSecond();
        var next = System.nanoTime();
        for (var i = 0; i < count; i++) {
            publisher.publish(new ProcessCreationRequested(
                    "bench-3-steps", prefix + "-" + i, List.of(), null));
            if (nanosBetween > 0) {
                next += nanosBetween;
                var wait = next - System.nanoTime();
                if (wait > 0) {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                }
            }
        }
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
