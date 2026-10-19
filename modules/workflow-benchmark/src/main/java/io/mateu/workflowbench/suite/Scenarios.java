package io.mateu.workflowbench.suite;

import io.mateu.workflow.application.sync.SyncInvocationService;
import io.mateu.workflow.dtos.Variable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

/**
 * The six scenarios. Each returns one repetition's numbers as a flat map, which the suite
 * aggregates across repetitions (median, min, max) and writes out.
 *
 * <p>Two rules every scenario follows, because breaking either makes a number mean something
 * other than what its label says:
 * <ul>
 *   <li><b>Throughput and latency are separate phases.</b> Throughput is measured by submitting the
 *       whole batch at once (saturation); latency is measured afterwards, paced at half the
 *       throughput just measured. Latency under saturation is queue depth, not cost.</li>
 *   <li><b>Paced latency is measured from the intended send time</b>, not the moment a busy driver
 *       got round to it, so a stalled driver cannot hide a stall in the engine (coordinated
 *       omission).</li>
 * </ul>
 */
final class Scenarios {

    static final int DRIVER_THREADS = Integer.getInteger("suite.driver-threads", 8);
    static final Duration AWAIT = Duration.ofMinutes(Integer.getInteger("suite.await-minutes", 30));

    private final Engine engine;
    private final Probe probe;
    private final double scale;
    private final String runId;
    private final ZoneId zone = ZoneId.systemDefault();
    private final long baseEpochNanos;
    private final long baseNano;

    Scenarios(Engine engine, double scale, String runId) {
        this.engine = engine;
        this.probe = engine.probe();
        this.scale = scale;
        this.runId = runId;
        var now = java.time.Instant.now();
        baseNano = System.nanoTime();
        baseEpochNanos = now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }

    int scaled(int count) {
        return Math.max(5, (int) Math.round(count * scale));
    }

    // ------------------------------------------------------------------ the scenarios

    Map<String, Object> sequential(String rep, boolean warmup) throws Exception {
        return flow("seq", rep, "suite-sequential", List.of(), "COMPLETED",
                scaled(warmup ? 400 : 2000), warmup ? 5 : 20, 1);
    }

    Map<String, Object> saga(String rep, boolean warmup) throws Exception {
        return flow("saga", rep, "suite-saga", List.of(), "COMPENSATED",
                scaled(warmup ? 200 : 1000), warmup ? 5 : 20, 1);
    }

    Map<String, Object> fanout(String rep, boolean warmup) throws Exception {
        var items = "[" + String.join(",", IntStream.range(0, 50).mapToObj(String::valueOf).toList()) + "]";
        var result = flow("fan", rep, "suite-fanout", List.of(new Variable("items", items)), "COMPLETED",
                scaled(warmup ? 20 : 100), warmup ? 5 : 20, 50);
        result.put("itemsPerProcess", 50);
        return result;
    }

    /** A TIMER PT1S in each of N processes: how late does it fire, and how many processes per second get through. */
    Map<String, Object> timers(String rep, boolean warmup) throws Exception {
        var n = scaled(warmup ? 1000 : 10_000);
        var prefix = prefix("tmr", rep);
        var out = new LinkedHashMap<String, Object>();
        try (var sampler = new ResourceSampler()) {
            var t0 = now();
            submitAll(n, i -> engine.create("suite-timer", prefix + i, List.of()));
            out.put("submitSeconds", Stats.round((now() - t0) / 1e9));
            awaitTerminal(prefix, n);
            var rows = probe.steps(prefix, "wait");
            var lateness = new ArrayList<Double>();
            var lastFinished = 0L;
            for (var row : rows) {
                if (row.finishedAt() == null || row.startedAt() == null) {
                    continue;
                }
                var due = row.deadlineAt() != null ? row.deadlineAt() : row.startedAt().plusSeconds(1);
                lateness.add((epochNanos(row.finishedAt()) - epochNanos(due)) / 1e6);
                lastFinished = Math.max(lastFinished, epochNanos(row.finishedAt()));
            }
            out.put("processes", n);
            out.put("statuses", statuses(prefix));
            out.put("throughputPerSec", Stats.round(n / ((latestFinish(prefix) - t0) / 1e9)));
            out.put("latenessMs", Stats.latency(lateness));
            out.put("resources", sampler.result());
        }
        purge(prefix);
        return out;
    }

    /** N processes parked on a WAIT_FOR_MESSAGE, then N messages: how fast are they correlated and resumed. */
    Map<String, Object> messages(String rep, boolean warmup) throws Exception {
        var n = scaled(warmup ? 1000 : 10_000);
        var prefix = prefix("msg", rep);
        var out = new LinkedHashMap<String, Object>();
        submitAll(n, i -> engine.create("suite-message", prefix + i, List.of()));
        awaitCount(() -> probe.countPending(prefix, "wait"), n, "waiting on the message");
        try (var sampler = new ResourceSampler()) {
            var sent = new ConcurrentHashMap<String, Long>();
            var t0 = now();
            submitAll(n, i -> {
                sent.put(prefix + i, now());
                engine.message("suite-go", prefix + i);
            });
            out.put("submitSeconds", Stats.round((now() - t0) / 1e9));
            awaitTerminal(prefix, n);
            out.put("messages", n);
            out.put("statuses", statuses(prefix));
            out.put("throughputPerSec", Stats.round(n / ((latestFinish(prefix) - t0) / 1e9)));
            out.put("messageToEndMs", Stats.latency(latencies(prefix, sent)));
            out.put("resources", sampler.result());
        }
        purge(prefix);
        return out;
    }

    /** Closed-loop synchronous invocations (START → ACTION → REPLY → END) at several concurrencies. */
    Map<String, Object> sync(String rep, boolean warmup) throws Exception {
        var out = new LinkedHashMap<String, Object>();
        var service = engine.sync();
        var seconds = warmup ? 3 : Integer.getInteger("suite.sync-seconds", 15);
        for (var concurrency : List.of(1, 4, 16)) {
            var prefix = prefix("sync" + concurrency, rep);
            var samples = Collections.synchronizedList(new ArrayList<Double>());
            var failures = new AtomicInteger();
            var counter = new AtomicLong();
            var deadline = System.nanoTime() + seconds * 1_000_000_000L;
            try (var sampler = new ResourceSampler()) {
                var pool = Executors.newFixedThreadPool(concurrency);
                var start = System.nanoTime();
                for (var t = 0; t < concurrency; t++) {
                    pool.submit(() -> {
                        while (System.nanoTime() < deadline) {
                            var key = prefix + counter.incrementAndGet();
                            var began = System.nanoTime();
                            try {
                                var started = service.start(new SyncInvocationService.StartRequest(
                                        "suite-sync", UUID.randomUUID().toString(), key, Map.of(),
                                        Duration.ofSeconds(30), null));
                                var view = service.await(started.invocation(), started.timeToWait()).join();
                                if (view.replied()) {
                                    samples.add((System.nanoTime() - began) / 1e6);
                                } else {
                                    failures.incrementAndGet();
                                }
                            } catch (RuntimeException e) {
                                failures.incrementAndGet();
                            }
                        }
                    });
                }
                pool.shutdown();
                pool.awaitTermination(seconds + 120L, TimeUnit.SECONDS);
                var elapsed = (System.nanoTime() - start) / 1e9;
                var level = new LinkedHashMap<String, Object>();
                level.put("requests", samples.size());
                level.put("failures", failures.get());
                level.put("throughputPerSec", Stats.round(samples.size() / elapsed));
                level.put("latencyMs", Stats.latency(samples));
                level.put("resources", sampler.result());
                out.put("c" + concurrency, level);
            }
            awaitTerminal(prefix, (int) counter.get());
            purge(prefix);
        }
        return out;
    }

    /**
     * Does the engine slow down as history piles up? Five consecutive batches of the sequential
     * definition, never purged in between: a store whose per-transition cost grows with the number
     * of rows it holds shows up as a falling throughput from batch to batch. Opt-in (not in the
     * default scenario list) — it is a regression check, not a headline number.
     */
    Map<String, Object> growth(String rep, boolean warmup) throws Exception {
        var out = new LinkedHashMap<String, Object>();
        var batches = warmup ? 1 : Integer.getInteger("suite.growth-batches", 5);
        var n = scaled(warmup ? 400 : 4000);
        var prefixes = new ArrayList<String>();
        for (var b = 1; b <= batches; b++) {
            var prefix = prefix("grow-b" + b, rep);
            prefixes.add(prefix);
            var t0 = now();
            submitAll(n, i -> engine.create("suite-sequential", prefix + i, List.of()));
            awaitTerminal(prefix, n);
            out.put("batch" + b + "ThroughputPerSec", Stats.round(n / ((latestFinish(prefix) - t0) / 1e9)));
        }
        out.put("processesPerBatch", n);
        for (var prefix : prefixes) {
            purge(prefix);
        }
        return out;
    }

    // ------------------------------------------------------------------ the shared shape of a flow

    private Map<String, Object> flow(String name, String rep, String definition, List<Variable> variables,
                                     String expected, int n, int latencySeconds, int itemsPerProcess) throws Exception {
        var out = new LinkedHashMap<String, Object>();

        // Phase 1: saturation. Everything at once; throughput is the batch over the time it took.
        var prefix = prefix(name + "-t", rep);
        double throughput;
        try (var sampler = new ResourceSampler()) {
            var t0 = now();
            submitAll(n, i -> engine.create(definition, prefix + i, variables));
            awaitStatus(prefix, n, java.util.Set.of(expected));
            throughput = n / ((latestFinish(prefix) - t0) / 1e9);
            out.put("processes", n);
            out.put("statuses", statuses(prefix));
            out.put("unexpected", n - statusCount(prefix, expected));
            out.put("throughputPerSec", Stats.round(throughput));
            var steps = probe.stepExecutions(prefix);
            out.put("stepExecutionsPerProcess", Stats.round(steps / (double) n));
            out.put("stepExecutionsPerSec", Stats.round(throughput * steps / n));
            if (itemsPerProcess > 1) {
                out.put("itemsPerSec", Stats.round(throughput * itemsPerProcess));
            }
            out.put("resources", sampler.result());
        }
        purge(prefix);

        // Phase 2: paced at half that, so the latency is the path's cost and not a queue's depth.
        var latencyPrefix = prefix(name + "-l", rep);
        var rate = Math.max(1.0, Math.min(throughput * 0.5, Double.parseDouble(System.getProperty("suite.max-rate", "2000"))));
        var count = (int) Math.max(20, Math.min(5000, Math.round(rate * latencySeconds)));
        var intended = new ConcurrentHashMap<String, Long>();
        try (var sampler = new ResourceSampler()) {
            submitPaced(rate, count, (i, at) -> {
                intended.put(latencyPrefix + i, at);
                engine.create(definition, latencyPrefix + i, variables);
            });
            awaitStatus(latencyPrefix, count, java.util.Set.of(expected));
            out.put("latencyRatePerSec", Stats.round(rate));
            out.put("latencyProcesses", count);
            out.put("latencyMs", Stats.latency(latencies(latencyPrefix, intended)));
            out.put("latencyResources", sampler.result());
        }
        purge(latencyPrefix);
        return out;
    }

    // ------------------------------------------------------------------ driving

    private void submitAll(int n, IntConsumer task) throws InterruptedException {
        if (engine.asynchronousIngress()) {
            for (var i = 0; i < n; i++) {
                task.accept(i);
            }
            engine.flush();
            return;
        }
        // Embedded ingress runs the engine on the caller's thread, so the driver needs threads of
        // its own — as an application serving concurrent requests would.
        var next = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(DRIVER_THREADS);
        var errors = new AtomicInteger();
        for (var t = 0; t < DRIVER_THREADS; t++) {
            pool.submit(() -> {
                int i;
                while ((i = next.getAndIncrement()) < n) {
                    try {
                        task.accept(i);
                    } catch (RuntimeException e) {
                        if (errors.incrementAndGet() <= 3) {
                            System.err.println("submit failed: " + e);
                        }
                    }
                }
            });
        }
        shutdown(pool);
    }

    interface PacedTask {
        void run(int index, long intendedEpochNanos);
    }

    private void submitPaced(double ratePerSec, int n, PacedTask task) throws InterruptedException {
        ExecutorService pool = engine.asynchronousIngress() ? null : Executors.newFixedThreadPool(DRIVER_THREADS);
        var interval = (long) (1_000_000_000L / ratePerSec);
        var start = System.nanoTime();
        for (var i = 0; i < n; i++) {
            var dueNano = start + i * interval;
            var wait = dueNano - System.nanoTime();
            if (wait > 0) {
                TimeUnit.NANOSECONDS.sleep(wait);
            }
            var index = i;
            var intended = baseEpochNanos + (dueNano - baseNano);
            if (pool == null) {
                task.run(index, intended);
            } else {
                pool.submit(() -> task.run(index, intended));
            }
        }
        if (pool == null) {
            engine.flush();
        } else {
            shutdown(pool);
        }
    }

    private static void shutdown(ExecutorService pool) throws InterruptedException {
        pool.shutdown();
        if (!pool.awaitTermination(AWAIT.toSeconds(), TimeUnit.SECONDS)) {
            pool.shutdownNow();
            throw new IllegalStateException("driver did not finish submitting");
        }
    }

    // ------------------------------------------------------------------ reading back

    private static final java.util.Set<String> DONE = java.util.Set.of("COMPLETED");

    private void awaitTerminal(String prefix, int n) throws InterruptedException {
        awaitStatus(prefix, n, DONE);
    }

    private void awaitStatus(String prefix, int n, java.util.Set<String> statuses) throws InterruptedException {
        awaitCount(() -> probe.countIn(prefix, statuses), n, "in " + statuses);
    }

    /**
     * Purges a finished run, once the engine has nothing left to do for it. A process reaches its
     * final status before the last of its outbox rows (its end-of-process notifications) is
     * relayed; deleting it under them turns them into errors and the next repetition into a
     * measurement of the error path.
     */
    private void purge(String prefix) throws InterruptedException {
        awaitCount(() -> -probe.pendingOutbox(), 0, "outbox drained");
        if (engine.asynchronousIngress()) {
            Thread.sleep(1000); // relayed is not yet consumed: let the consumers finish
        }
        probe.purge(prefix);
    }

    private void awaitCount(java.util.function.LongSupplier count, long expected, String what) throws InterruptedException {
        var deadline = System.nanoTime() + AWAIT.toNanos();
        var last = -1L;
        var lastMoved = System.nanoTime();
        while (System.nanoTime() < deadline) {
            var current = count.getAsLong();
            if (current >= expected) {
                return;
            }
            if (current != last) {
                last = current;
                lastMoved = System.nanoTime();
            } else if (System.nanoTime() - lastMoved > Duration.ofMinutes(3).toNanos()) {
                throw new IllegalStateException("stuck: " + current + "/" + expected + " " + what
                        + " and no progress for 3 minutes");
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("timed out: only " + last + "/" + expected + " " + what);
    }

    private List<Double> latencies(String prefix, Map<String, Long> sentAt) {
        var out = new ArrayList<Double>();
        for (var row : probe.processes(prefix)) {
            var sent = sentAt.get(row.businessKey());
            if (sent != null && row.finished() != null) {
                out.add((epochNanos(row.finished()) - sent) / 1e6);
            }
        }
        return out;
    }

    private long latestFinish(String prefix) {
        return probe.processes(prefix).stream()
                .filter(r -> r.finished() != null)
                .mapToLong(r -> epochNanos(r.finished()))
                .max().orElse(now());
    }

    private Map<String, Long> statuses(String prefix) {
        var out = new java.util.TreeMap<String, Long>();
        probe.processes(prefix).forEach(r -> out.merge(r.status(), 1L, Long::sum));
        return out;
    }

    private long statusCount(String prefix, String status) {
        return probe.processes(prefix).stream().filter(r -> status.equals(r.status())).count();
    }

    private String prefix(String scenario, String rep) {
        return "sb-" + runId + "-" + engine.mode + "-" + scenario + "-" + rep + "-";
    }

    private long now() {
        return baseEpochNanos + (System.nanoTime() - baseNano);
    }

    private long epochNanos(LocalDateTime time) {
        var instant = time.atZone(zone).toInstant();
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }
}
