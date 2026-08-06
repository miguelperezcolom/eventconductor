package io.mateu.workflowbench.soak;

import io.mateu.workflowbench.LoadDriver;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Long-running load whose purpose is not speed but evidence.
 *
 * <p>The benchmark asks how fast; this asks whether anything was lost while the cluster was being
 * broken underneath it. The two need different drivers, because a driver that is allowed to fail
 * is useless here: if the process publishing the load dies when Kafka does, the run cannot tell
 * an engine that dropped work from a harness that never sent it.
 *
 * <p>So the count of what was truly handed over lives in the database, not in this JVM. Every
 * second the driver writes down how many creation events the broker has <em>acknowledged</em>
 * (with {@code acks=all} and an idempotent producer, so a retried send cannot become two
 * processes). That number is the contract: after the run, the engine must show exactly that many
 * processes, no more and no fewer. A pod, a node, or this driver itself can die at any point and
 * the number survives, which is what makes the verdict independent of the harness.
 *
 * <p>During an outage the send loop is expected to <em>block</em>, not to error: {@code
 * max.block.ms} and {@code delivery.timeout.ms} are set long enough to ride out the outages the
 * chaos scripts inject. The attempted-vs-acknowledged gap in the log is the shape of the outage.
 */
public final class SoakDriver {

    /**
     * Where the acknowledged count is kept. Written by the driver, read by the verifier, and the
     * only reason the two are not the same program.
     */
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS soak_progress (
              prefix     varchar(64) PRIMARY KEY,
              attempted  bigint    NOT NULL,
              acked      bigint    NOT NULL,
              failed     bigint    NOT NULL,
              started_at timestamp NOT NULL,
              updated_at timestamp NOT NULL
            )""";

    private static final String UPSERT = """
            INSERT INTO soak_progress (prefix, attempted, acked, failed, started_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (prefix) DO UPDATE SET
              attempted = excluded.attempted,
              acked = excluded.acked,
              failed = excluded.failed,
              updated_at = excluded.updated_at""";

    private final JdbcTemplate jdbc;
    private final String prefix;
    private final int ratePerSecond;
    private final Duration duration;
    private final Workload workload;

    private final AtomicLong attempted = new AtomicLong();
    private final AtomicLong acked = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final Instant startedAt = Instant.now();

    private volatile boolean stopping;

    public SoakDriver(JdbcTemplate jdbc, String prefix, int ratePerSecond, Duration duration,
                      Workload workload) {
        this.jdbc = jdbc;
        this.prefix = prefix;
        this.ratePerSecond = ratePerSecond;
        this.duration = duration;
        this.workload = workload;
    }

    public void run(LoadDriver loadDriver) throws InterruptedException {
        prepare();
        var reporter = startReporter();
        Runtime.getRuntime().addShutdownHook(new Thread(this::finish, "soak-shutdown"));

        System.out.println("soak: prefix=" + prefix + " rate=" + ratePerSecond + "/s duration="
                + (duration.isZero() ? "until stopped" : duration.toMinutes() + "m"));

        var nanosBetween = ratePerSecond <= 0 ? 0L : 1_000_000_000L / ratePerSecond;
        var deadline = duration.isZero() ? Long.MAX_VALUE : System.nanoTime() + duration.toNanos();
        var next = System.nanoTime();

        for (long i = 0; !stopping && System.nanoTime() < deadline; i++) {
            attempted.incrementAndGet();
            var creation = workload.at(prefix, i);
            loadDriver.createProcess(creation.definitionId(), creation.businessKey(),
                    creation.variables(), (metadata, error) -> {
                if (error == null) {
                    acked.incrementAndGet();
                } else {
                    // Counted, never swallowed. A failed send means the engine was never asked to
                    // do the work, so it is not a loss — but a run with a non-zero failure count
                    // is a run whose conservation check has a hole in it, and the report says so.
                    failed.incrementAndGet();
                }
            });
            if (nanosBetween > 0) {
                next += nanosBetween;
                var wait = next - System.nanoTime();
                if (wait > 0) {
                    Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                } else if (wait < -1_000_000_000L) {
                    // Fell more than a second behind — during an outage this is expected, and
                    // catching up by firing a burst would misrepresent the arrival rate.
                    next = System.nanoTime();
                }
            }
        }

        stopping = true;
        loadDriver.flush();
        reporter.interrupt();
        finish();
    }

    private Thread startReporter() {
        var thread = new Thread(() -> {
            while (!stopping) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                publishProgress();
            }
        }, "soak-reporter");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Writes the counters down and says so on stdout.
     *
     * <p>Failures here are logged and ignored on purpose: the database going away is one of the
     * things this run exists to survive, and a driver that dies with it would prove nothing. The
     * counters are cumulative, so the next write that succeeds restores the truth.
     */
    private void publishProgress() {
        var now = Instant.now();
        var attemptedNow = attempted.get();
        var ackedNow = acked.get();
        var failedNow = failed.get();
        String dbState;
        try {
            jdbc.update(UPSERT, prefix, attemptedNow, ackedNow, failedNow,
                    java.sql.Timestamp.from(startedAt), java.sql.Timestamp.from(now));
            dbState = "ok";
        } catch (RuntimeException e) {
            dbState = "unreachable(" + e.getClass().getSimpleName() + ")";
        }
        System.out.println("soak t=" + Duration.between(startedAt, now).toSeconds() + "s"
                + " attempted=" + attemptedNow
                + " acked=" + ackedNow
                + " inflight=" + (attemptedNow - ackedNow - failedNow)
                + " failed=" + failedNow
                + " db=" + dbState);
    }

    private void finish() {
        stopping = true;
        // Best effort, retried, because the final number is the one the verdict is computed from.
        for (var attempt = 0; attempt < 30; attempt++) {
            try {
                jdbc.update(UPSERT, prefix, attempted.get(), acked.get(), failed.get(),
                        java.sql.Timestamp.from(startedAt), java.sql.Timestamp.from(Instant.now()));
                System.out.println("soak done: prefix=" + prefix + " attempted=" + attempted.get()
                        + " acked=" + acked.get() + " failed=" + failed.get());
                return;
            } catch (RuntimeException e) {
                sleepQuietly();
            }
        }
        System.out.println("soak done: could not record the final count — prefix=" + prefix
                + " acked=" + acked.get() + " (the verifier will read a stale figure)");
    }

    /**
     * Creates the progress table and installs the definition, retrying because the database may
     * still be starting — and because a soak that refuses to boot into a degraded cluster cannot
     * be started <em>during</em> an outage, which is a thing worth being able to do.
     */
    private void prepare() throws InterruptedException {
        for (var attempt = 0; ; attempt++) {
            try {
                jdbc.execute(CREATE_TABLE);
                for (var definition : workload.definitions()) {
                    WorkflowInstaller.install(jdbc, definition);
                }
                return;
            } catch (RuntimeException e) {
                if (attempt == 150) {
                    throw new IllegalStateException("the soak could not reach the database", e);
                }
                Thread.sleep(2000);
            }
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
