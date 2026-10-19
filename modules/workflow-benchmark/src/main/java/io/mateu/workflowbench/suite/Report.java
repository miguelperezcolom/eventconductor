package io.mateu.workflowbench.suite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregates repetitions and renders the Markdown report. The JSON is the source; this is a view of it. */
final class Report {

    /** For every numeric leaf (dotted path) across the repetitions: median, min, max. */
    static Map<String, Object> summarize(List<Map<String, Object>> runs) {
        var values = new LinkedHashMap<String, List<Double>>();
        for (var run : runs) {
            flatten("", run, values);
        }
        var out = new LinkedHashMap<String, Object>();
        values.forEach((path, list) -> {
            var cell = new LinkedHashMap<String, Object>();
            cell.put("median", Stats.round(Stats.median(list)));
            cell.put("min", Stats.round(Stats.min(list)));
            cell.put("max", Stats.round(Stats.max(list)));
            out.put(path, cell);
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> map, Map<String, List<Double>> into) {
        map.forEach((key, value) -> {
            var path = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof Map<?, ?> nested) {
                flatten(path, (Map<String, Object>) nested, into);
            } else if (value instanceof Number number) {
                into.computeIfAbsent(path, k -> new ArrayList<>()).add(number.doubleValue());
            }
        });
    }

    private record Row(String label, String path, boolean range) {
    }

    private static final Map<String, List<Row>> ROWS = new LinkedHashMap<>();

    static {
        var flow = List.of(
                new Row("throughput (processes/s)", "throughputPerSec", true),
                new Row("step executions per process", "stepExecutionsPerProcess", false),
                new Row("step executions/s", "stepExecutionsPerSec", true),
                new Row("latency phase: paced at (processes/s)", "latencyRatePerSec", false),
                new Row("end-to-end latency p50 (ms)", "latencyMs.p50", false),
                new Row("end-to-end latency p95 (ms)", "latencyMs.p95", false),
                new Row("end-to-end latency p99 (ms)", "latencyMs.p99", false),
                new Row("JVM CPU, saturation phase (cores, avg)", "resources.jvmCpuAvgCores", false),
                new Row("machine CPU, saturation phase (% avg)", "resources.machineCpuAvgPct", false),
                new Row("JVM heap used, max (MB)", "resources.heapMaxUsedMb", false));
        ROWS.put("sequential", flow);
        ROWS.put("saga", flow);
        var fan = new ArrayList<>(flow);
        fan.add(1, new Row("items/s", "itemsPerSec", true));
        ROWS.put("fanout", fan);
        ROWS.put("timers", List.of(
                new Row("throughput (processes/s)", "throughputPerSec", true),
                new Row("time to submit all (s)", "submitSeconds", false),
                new Row("lateness p50 (ms)", "latenessMs.p50", false),
                new Row("lateness p95 (ms)", "latenessMs.p95", false),
                new Row("lateness p99 (ms)", "latenessMs.p99", false),
                new Row("lateness max (ms)", "latenessMs.max", false),
                new Row("JVM CPU (cores, avg)", "resources.jvmCpuAvgCores", false),
                new Row("machine CPU (% avg)", "resources.machineCpuAvgPct", false),
                new Row("JVM heap used, max (MB)", "resources.heapMaxUsedMb", false)));
        ROWS.put("messages", List.of(
                new Row("correlation throughput (messages/s)", "throughputPerSec", true),
                new Row("message → process end p50 (ms)", "messageToEndMs.p50", false),
                new Row("message → process end p95 (ms)", "messageToEndMs.p95", false),
                new Row("message → process end p99 (ms)", "messageToEndMs.p99", false),
                new Row("JVM CPU (cores, avg)", "resources.jvmCpuAvgCores", false),
                new Row("machine CPU (% avg)", "resources.machineCpuAvgPct", false),
                new Row("JVM heap used, max (MB)", "resources.heapMaxUsedMb", false)));
        var sync = new ArrayList<Row>();
        for (var c : List.of(1, 4, 16)) {
            sync.add(new Row("c=" + c + ": requests/s", "c" + c + ".throughputPerSec", true));
            sync.add(new Row("c=" + c + ": latency p50 (ms)", "c" + c + ".latencyMs.p50", false));
            sync.add(new Row("c=" + c + ": latency p95 (ms)", "c" + c + ".latencyMs.p95", false));
            sync.add(new Row("c=" + c + ": latency p99 (ms)", "c" + c + ".latencyMs.p99", false));
            sync.add(new Row("c=" + c + ": failed requests", "c" + c + ".failures", false));
        }
        ROWS.put("sync", sync);
        var growth = new ArrayList<Row>();
        for (var b = 1; b <= 5; b++) {
            growth.add(new Row("batch " + b + " throughput (processes/s)", "batch" + b + "ThroughputPerSec", true));
        }
        ROWS.put("growth", growth);
    }

    @SuppressWarnings("unchecked")
    static String markdown(Map<String, Object> report) {
        var md = new StringBuilder();
        md.append("# EventConductor local benchmark — run ").append(report.get("runId")).append("\n\n");
        md.append("Numbers for **this machine**, everything on one host. Medians over the repetitions; ")
                .append("throughput cells show `median (min–max)`. See the JSON for every repetition.\n\n");
        md.append("## Environment\n\n");
        section(md, (Map<String, Object>) report.get("environment"));
        section(md, (Map<String, Object>) report.get("versions"));
        section(md, (Map<String, Object>) report.get("parameters"));

        var results = (Map<String, Object>) report.get("results");
        var modes = new ArrayList<>(results.keySet());
        for (var entry : ROWS.entrySet()) {
            var scenario = entry.getKey();
            if (modes.stream().noneMatch(m -> ((Map<String, Object>) results.get(m)).containsKey(scenario))) {
                continue;
            }
            md.append("## ").append(scenario).append("\n\n| metric |");
            modes.forEach(m -> md.append(' ').append(m).append(" |"));
            md.append("\n|---|");
            modes.forEach(m -> md.append("---:|"));
            md.append('\n');
            for (var row : entry.getValue()) {
                md.append("| ").append(row.label()).append(" |");
                for (var mode : modes) {
                    md.append(' ').append(cell((Map<String, Object>) results.get(mode), scenario, row)).append(" |");
                }
                md.append('\n');
            }
            for (var mode : modes) {
                var s = (Map<String, Object>) ((Map<String, Object>) results.get(mode)).get(scenario);
                if (s != null && s.get("error") != null) {
                    md.append("\n`").append(mode).append("` error: ").append(s.get("error")).append('\n');
                }
            }
            md.append('\n');
        }
        md.append("## Mode settings\n\n");
        var settings = (Map<String, Object>) report.get("modeSettings");
        settings.forEach((mode, values) -> {
            md.append("### ").append(mode).append("\n\n");
            section(md, (Map<String, Object>) values);
        });
        md.append("## Containers (docker stats, end of each mode)\n\n");
        section(md, (Map<String, Object>) report.get("containersAfterMode"));
        return md.toString();
    }

    @SuppressWarnings("unchecked")
    private static String cell(Map<String, Object> modeResults, String scenario, Row row) {
        var s = (Map<String, Object>) modeResults.get(scenario);
        if (s == null) {
            return "–";
        }
        var summary = (Map<String, Object>) s.get("summary");
        var value = summary == null ? null : (Map<String, Object>) summary.get(row.path());
        if (value == null) {
            return "–";
        }
        var median = format((Double) value.get("median"));
        return row.range()
                ? median + " (" + format((Double) value.get("min")) + "–" + format((Double) value.get("max")) + ")"
                : median;
    }

    private static String format(double v) {
        if (Double.isNaN(v)) {
            return "–";
        }
        if (Math.abs(v) >= 100) {
            return String.format("%,.0f", v);
        }
        if (Math.abs(v) >= 10) {
            return String.format("%.1f", v);
        }
        return String.format("%.2f", v);
    }

    private static void section(StringBuilder md, Map<String, Object> values) {
        if (values == null) {
            return;
        }
        values.forEach((k, v) -> md.append("- **").append(k).append("**: ").append(v).append('\n'));
        md.append('\n');
    }

    private Report() {
    }
}
