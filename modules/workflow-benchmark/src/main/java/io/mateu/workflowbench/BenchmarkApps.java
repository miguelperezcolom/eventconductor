package io.mateu.workflowbench;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The two Spring applications the harness runs: orchestrator pods and a worker. */
final class BenchmarkApps {

    static ConfigurableApplicationContext startOrchestrator(BenchmarkConfig config, int index) {
        var props = common(config);
        props.put("spring.application.name", "bench-orchestrator-" + index);
        props.put("spring.cloud.function.definition", "consumeOutbox;consumeUpstream");
        props.put("workflow.outbox-poll-interval-ms", String.valueOf(config.outboxPollMillis()));
        props.put("workflow.outbox.batch-size", String.valueOf(config.outboxBatchSize()));
        props.put("workflow.timeout-scan-interval-ms", "1000");
        props.put("workflow.cron-enabled", "false");
        props.put("spring.datasource.url", config.jdbcUrl());
        props.put("spring.datasource.username", config.jdbcUser());
        props.put("spring.datasource.password", config.jdbcPassword());
        props.put("spring.datasource.hikari.maximum-pool-size", String.valueOf(config.poolSize()));
        props.put("spring.jpa.hibernate.ddl-auto", "update");
        props.put("spring.jpa.open-in-view", "false");
        props.put("spring.cloud.stream.bindings.consumeOutbox-in-0.destination", topic("outbox", config.shard()));
        props.put("spring.cloud.stream.bindings.consumeOutbox-in-0.group", "orchestrator-outbox");
        props.put("spring.cloud.stream.bindings.consumeOutbox-in-0.consumer.batch-mode", "true");
        props.put("spring.cloud.stream.bindings.consumeOutbox-in-0.consumer.concurrency",
                String.valueOf(config.consumerConcurrency()));
        props.put("spring.cloud.stream.bindings.consumeUpstream-in-0.destination", topic("upstream", config.shard()));
        props.put("spring.cloud.stream.bindings.consumeUpstream-in-0.group", "orchestrator-upstream");
        props.put("spring.cloud.stream.bindings.consumeUpstream-in-0.consumer.batch-mode", "true");
        props.put("spring.cloud.stream.bindings.consumeUpstream-in-0.consumer.concurrency",
                String.valueOf(config.consumerConcurrency()));
        props.put("spring.cloud.stream.bindings.outbox.destination", topic("outbox", config.shard()));
        props.put("spring.cloud.stream.bindings.downstream.destination", topic("downstream", config.shard()));
        props.put("spring.cloud.stream.bindings.deadLetter.destination", topic("dead-letter", config.shard()));
        return run(io.mateu.workflowbench.orchestrator.BenchmarkOrchestratorApp.class, props);
    }

    static ConfigurableApplicationContext startWorker(BenchmarkConfig config) {
        var props = common(config);
        props.put("spring.application.name", "bench-worker");
        // Sharded worker reply routing: the worker replies through StreamBridge.send("upstream", …) in
        // the engine's WorkerReply. StreamBridge's dynamic send does NOT honour the low-precedence
        // default property common() sets below, so on a sharded run the reply must instead carry the
        // shard-suffixed destination as a high-precedence override. The worker deployment supplies it as
        // the ENV `SPRING_CLOUD_STREAM_BINDINGS_UPSTREAM_DESTINATION=upstream-<shard>` (systemEnvironment),
        // exactly as the orchestrator does — see k8s/scale/sharded/70-worker-shard.yaml. Without it every
        // reply lands on the plain `upstream` topic that no sharded orchestrator consumes, and every step
        // times out. Verified on a 2-shard cluster smoke: with the ENV, all invariants pass.
        props.put("spring.cloud.function.definition", "consumeWorkerEvent");
        props.put("spring.cloud.stream.bindings.consumeWorkerEvent-in-0.destination", topic("downstream", config.shard()));
        props.put("spring.cloud.stream.bindings.consumeWorkerEvent-in-0.group", "worker-group");
        props.put("spring.cloud.stream.bindings.consumeWorkerEvent-in-0.consumer.concurrency",
                String.valueOf(config.workerConcurrency()));
        return run(io.mateu.workflowbench.worker.BenchmarkWorkerApp.class, props);
    }

    /** A shard's per-shard topic name ({@code base-shard}), or the plain base when not sharded. */
    private static String topic(String base, String shard) {
        return (shard == null || shard.isBlank()) ? base : base + "-" + shard;
    }

    private static Map<String, Object> common(BenchmarkConfig config) {
        var props = new HashMap<String, Object>();
        props.put("spring.main.web-application-type", "none");
        props.put("spring.main.banner-mode", "off");
        props.put("workflow.mode", "kafka");
        props.put("workflow.persistence", "jpa");
        props.put("spring.cloud.stream.kafka.binder.brokers", config.kafkaBrokers());
        props.put("spring.cloud.stream.bindings.upstream.destination", topic("upstream", config.shard()));
        props.put("logging.level.root", "WARN");
        props.put("logging.level.io.mateu.workflow", "WARN");
        return props;
    }

    private static ConfigurableApplicationContext run(Class<?> app, Map<String, Object> props) {
        return new SpringApplicationBuilder(app).web(WebApplicationType.NONE).properties(props).run();
    }

    static List<String> topics() {
        return List.of("upstream", "downstream", "outbox", "dead-letter");
    }

    private BenchmarkApps() {
    }
}
