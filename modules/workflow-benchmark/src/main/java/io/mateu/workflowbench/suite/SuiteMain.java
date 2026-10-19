package io.mateu.workflowbench.suite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The local benchmark suite: six scenarios, up to four deployment modes, several repetitions each,
 * one JSON and one Markdown report. Run it with {@code scripts/benchmark.sh}.
 *
 * <p>Everything — engine pods, the no-op worker, the load driver — runs in this one JVM, and
 * PostgreSQL and Redpanda run in containers on the same machine. That makes the figures a
 * description of this machine running EventConductor, good for comparing modes and versions with
 * each other and for an order of magnitude; it is not a capacity claim, and the report says so.
 *
 * <p>Knobs (system properties): {@code suite.modes} (memory,jpa,kafka1,kafka2),
 * {@code suite.scenarios} (sequential,saga,fanout,timers,messages,sync), {@code suite.reps} (3),
 * {@code suite.scale} (1.0 — multiplies every count; the CI smoke uses 0.01), {@code suite.out}
 * (output directory), {@code suite.engine.*} (passed to every engine context verbatim).
 */
public final class SuiteMain {

    public static void main(String[] args) throws Exception {
        var modes = list("suite.modes", "memory,jpa,kafka1,kafka2");
        var scenarios = list("suite.scenarios", "sequential,saga,fanout,timers,messages,sync");
        var reps = Integer.getInteger("suite.reps", 3);
        var scale = Double.parseDouble(System.getProperty("suite.scale", "1.0"));
        var out = Path.of(System.getProperty("suite.out", "target/benchmark"));
        var runId = DateTimeFormatter.ofPattern("MMddHHmmss").format(LocalDateTime.now());
        Files.createDirectories(out);

        var report = new LinkedHashMap<String, Object>();
        report.put("runId", runId);
        report.put("startedAt", LocalDateTime.now().toString());
        report.put("environment", environment());
        report.put("parameters", Map.of("modes", modes, "scenarios", scenarios, "repetitions", reps,
                "scale", scale, "driverThreads", Scenarios.DRIVER_THREADS));
        var results = new LinkedHashMap<String, Object>();
        report.put("results", results);
        var settings = new LinkedHashMap<String, Object>();
        report.put("modeSettings", settings);
        var containers = new LinkedHashMap<String, Object>();
        report.put("containersAfterMode", containers);

        try (var infra = new Infra()) {
            for (var mode : modes) {
                System.out.println("\n=== mode " + mode + " ===");
                var modeResults = new LinkedHashMap<String, Object>();
                results.put(mode, modeResults);
                try (var engine = start(mode, infra)) {
                    settings.put(mode, engine.settings);
                    var suite = new Scenarios(engine, scale, runId);
                    for (var scenario : scenarios) {
                        modeResults.put(scenario, run(suite, scenario, reps));
                        write(out, report, infra);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    modeResults.put("error", e.toString());
                }
                containers.put(mode, infra.containerMemory());
                write(out, report, infra);
            }
            report.put("finishedAt", LocalDateTime.now().toString());
            report.put("loadAverageAtEnd", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
            write(out, report, infra);
        }
        System.out.println("\nReport: " + out.resolve("benchmark.json") + " and " + out.resolve("benchmark.md"));
    }

    private static Engine start(String mode, Infra infra) {
        return switch (mode) {
            case "memory" -> new Engine.Embedded("memory", null);
            case "jpa" -> new Engine.Embedded("jpa", infra.freshDatabase("mode_jpa"));
            case "kafka1" -> new Engine.Kafka("kafka1", 1, infra.freshDatabase("mode_kafka1"), infra.kafkaBootstrap(), infra);
            case "kafka2" -> new Engine.Kafka("kafka2", 2, infra.freshDatabase("mode_kafka2"), infra.kafkaBootstrap(), infra);
            default -> throw new IllegalArgumentException("unknown mode " + mode);
        };
    }

    /** One warm-up repetition (discarded), then {@code reps} measured ones, each starting from a purged store. */
    private static Map<String, Object> run(Scenarios suite, String scenario, int reps) {
        var out = new LinkedHashMap<String, Object>();
        var runs = new ArrayList<Map<String, Object>>();
        try {
            System.out.println("--- " + scenario + " warm-up");
            invoke(suite, scenario, "w", true);
            for (var rep = 1; rep <= reps; rep++) {
                var started = System.nanoTime();
                var result = invoke(suite, scenario, "r" + rep, false);
                result.put("loadAverage", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
                result.put("wallSeconds", Stats.round((System.nanoTime() - started) / 1e9));
                runs.add(result);
                System.out.println("--- " + scenario + " rep " + rep + ": " + result);
            }
        } catch (Exception e) {
            e.printStackTrace();
            out.put("error", e.toString());
        }
        out.put("summary", Report.summarize(runs));
        out.put("repetitions", runs);
        return out;
    }

    private static Map<String, Object> invoke(Scenarios suite, String scenario, String rep, boolean warmup) throws Exception {
        return switch (scenario) {
            case "sequential" -> suite.sequential(rep, warmup);
            case "saga" -> suite.saga(rep, warmup);
            case "fanout" -> suite.fanout(rep, warmup);
            case "timers" -> suite.timers(rep, warmup);
            case "messages" -> suite.messages(rep, warmup);
            case "sync" -> suite.sync(rep, warmup);
            case "growth" -> suite.growth(rep, warmup);
            default -> throw new IllegalArgumentException("unknown scenario " + scenario);
        };
    }

    private static Map<String, Object> environment() {
        var env = new LinkedHashMap<String, Object>();
        env.put("ecCommit", System.getProperty("suite.commit", "unknown"));
        env.put("ecVersion", System.getProperty("suite.version", "unknown"));
        env.put("machine", System.getProperty("suite.machine", "unknown"));
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        env.put("cpus", Runtime.getRuntime().availableProcessors());
        env.put("jdk", System.getProperty("java.vm.vendor") + " " + System.getProperty("java.runtime.version"));
        // The output directory is a local path and says nothing about the measurement.
        env.put("jvmArgs", ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(arg -> !arg.startsWith("-Dsuite.out=")).toList());
        env.put("maxHeapMb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        env.put("docker", System.getProperty("suite.docker", "unknown"));
        env.put("loadAverageAtStart", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
        env.put("backgroundLoad", System.getProperty("suite.background", "not recorded"));
        return env;
    }

    private static void write(Path out, Map<String, Object> report, Infra infra) throws Exception {
        report.put("versions", infra.versions());
        var json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(out.resolve("benchmark.json"), json.writeValueAsString(report));
        Files.writeString(out.resolve("benchmark.md"), Report.markdown(report));
    }

    private static List<String> list(String property, String fallback) {
        return Arrays.stream(System.getProperty(property, fallback).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private SuiteMain() {
    }
}
