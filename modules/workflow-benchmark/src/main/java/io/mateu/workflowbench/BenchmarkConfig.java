package io.mateu.workflowbench;

/**
 * Everything the harness needs, taken from system properties so a sweep is a shell loop.
 *
 * <p>Nothing is defaulted to a value that hides where it ran: the report prints all of this back,
 * because a throughput figure without its conditions is not a measurement.
 */
record BenchmarkConfig(
        String jdbcUrl, String jdbcUser, String jdbcPassword,
        String kafkaBrokers,
        int pods, int processes, int workerThinkMillis, int workerConcurrency,
        int consumerConcurrency, long outboxPollMillis, int outboxBatchSize, int poolSize,
        int ratePerSecond, String role,
        int soakMinutes, String soakPrefix,
        String workload, int sagaWeightPct, int compPermil, int compFailPermil,
        // Sharding (empty = single cluster, unchanged). `shards` is the active-shard list the driver
        // round-robins over and the reconciler fans out across; `shard` is one worker's own shard.
        String shards, String shard) {

    static BenchmarkConfig fromSystemProperties() {
        return new BenchmarkConfig(
                prop("bench.jdbc.url", "jdbc:postgresql://localhost:55432/eventconductor"),
                prop("bench.jdbc.user", "eventconductor"),
                prop("bench.jdbc.password", "eventconductor"),
                prop("bench.kafka.brokers", "localhost:59092"),
                intProp("bench.pods", 2),
                intProp("bench.processes", 2000),
                // The knob that separates the two questions. At 0 the measurement is all engine;
                // raise it and you are measuring how much work your workers can get through,
                // which is the real ceiling in production and not the engine's to answer.
                intProp("bench.worker.think-ms", 0),
                intProp("bench.worker.concurrency", 16),
                intProp("bench.consumer.concurrency", 3),
                Long.getLong("bench.outbox.poll-ms", 20),
                intProp("bench.outbox.batch-size", 100),
                intProp("bench.pool-size", 20),
                // Arrival rate, and the most important knob here. Firing everything at once
                // saturates the pipeline, and under saturation the gap between steps is mostly
                // queueing — a latency figure measured that way says how deep the backlog got,
                // not what a transition costs. Pace the load and it says the second thing.
                // 0 means unpaced: measure maximum throughput and disbelieve the latencies.
                intProp("bench.rate", 100),
                // all   — pods, worker and driver in this JVM. Convenient, and the only honest
                //         reading of it is "this build against that build, on this box".
                // pods  — orchestrators only, to run on their own host.
                // worker— workers only, likewise.
                // drive — load and measurement only, against pods running elsewhere. This is the
                //         one that produces a figure worth publishing.
                // soak  — load only, for hours, against an engine somebody else is breaking. It
                //         measures nothing; it leaves evidence.
                prop("bench.role", "all"),
                intProp("bench.soak.minutes", 0),
                // Namespaces the run's business keys. Defaulted to the hostname because in
                // Kubernetes that is the pod name, so a driver that is rescheduled mid-run does
                // not silently reuse the keys of the one that died.
                prop("bench.soak.prefix", "soak-" + hostname()),
                // Which workload the soak drives:
                //   linear — the single 3-ACTION definition (throughput/control).
                //   scale  — the realistic suite (order-saga + linear …), with a configured
                //            fraction of processes made to fail so the retry, saga-compensation
                //            and COMPENSATION_FAILED paths are exercised at volume.
                prop("bench.workload", "linear"),
                // scale only: % of processes that run the order-saga (rest run linear).
                intProp("bench.scale.saga-weight-pct", 40),
                // scale only: per-1000 saga processes that fail and roll back (→ COMPENSATED),
                // and — a subset of those — whose compensation also fails (→ COMPENSATION_FAILED).
                intProp("bench.scale.comp-permil", 100),
                intProp("bench.scale.comp-fail-permil", 10),
                prop("bench.shards", ""),
                prop("bench.shard", ""));
    }

    /** Active shards for the driver/reconciler; empty when not sharded. */
    java.util.List<String> shardList() {
        return (shards == null || shards.isBlank()) ? java.util.List.of()
                : java.util.Arrays.stream(shards.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    /** Per-shard jdbc urls: {@code jdbcUrl} with a {@code {shard}} placeholder expanded for each shard.
     *  With no shards (or no placeholder) it is just the single {@code jdbcUrl} — the reconciler then
     *  verifies one database exactly as before. */
    java.util.List<String> shardJdbcUrls() {
        var list = shardList();
        if (list.isEmpty() || !jdbcUrl.contains("{shard}")) {
            return java.util.List.of(jdbcUrl);
        }
        return list.stream().map(s -> jdbcUrl.replace("{shard}", s)).toList();
    }

    private static String hostname() {
        var name = System.getenv("HOSTNAME");
        return name == null || name.isBlank() ? "local" : name;
    }

    private static String prop(String name, String fallback) {
        return System.getProperty(name, fallback);
    }

    private static int intProp(String name, int fallback) {
        return Integer.getInteger(name, fallback);
    }

    boolean startsPods() {
        return "all".equals(role) || "pods".equals(role);
    }

    boolean startsWorker() {
        return "all".equals(role) || "worker".equals(role);
    }

    boolean drivesLoad() {
        return "all".equals(role) || "drive".equals(role);
    }

    boolean soaks() {
        return "soak".equals(role);
    }

    /** Everything in one JVM, which is the only case whose numbers describe a single machine. */
    boolean everythingIsLocal() {
        return "all".equals(role);
    }

    String describe() {
        return "role=" + role + " pods=" + pods
                + " processes=" + processes
                + " worker.think-ms=" + workerThinkMillis
                + " worker.concurrency=" + workerConcurrency
                + " consumer.concurrency=" + consumerConcurrency
                + " outbox.poll-ms=" + outboxPollMillis
                + " outbox.batch-size=" + outboxBatchSize
                + " pool-size=" + poolSize
                + " rate=" + (ratePerSecond == 0 ? "unpaced" : ratePerSecond + "/s")
                + " jdbc=" + jdbcUrl
                + " kafka=" + kafkaBrokers;
    }
}
