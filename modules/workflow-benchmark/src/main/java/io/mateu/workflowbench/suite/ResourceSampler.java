package io.mateu.workflowbench.suite;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Samples this JVM's CPU and heap, and the whole machine's CPU, every 500 ms while a phase runs.
 *
 * <p>The containers (PostgreSQL, Redpanda) run inside Docker Desktop's VM, so their CPU is not in
 * this process's figure — it shows up in the machine-wide one, which is why both are recorded.
 * Heap is "used after the phase", sampled, not a GC-accurate live set.
 */
final class ResourceSampler implements AutoCloseable {

    private final com.sun.management.OperatingSystemMXBean os =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private final Thread thread;
    private volatile boolean running = true;
    private double processCpuSum;
    private double processCpuMax;
    private double systemCpuSum;
    private long heapMaxUsed;
    private int samples;

    ResourceSampler() {
        thread = new Thread(this::loop, "suite-resource-sampler");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        // The first reading of getProcessCpuLoad is meaningless (no previous interval); discard it.
        os.getProcessCpuLoad();
        os.getCpuLoad();
        while (running) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            var process = os.getProcessCpuLoad();
            var system = os.getCpuLoad();
            var heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            synchronized (this) {
                if (process >= 0) {
                    processCpuSum += process;
                    processCpuMax = Math.max(processCpuMax, process);
                }
                if (system >= 0) {
                    systemCpuSum += system;
                }
                heapMaxUsed = Math.max(heapMaxUsed, heap);
                samples++;
            }
        }
    }

    /**
     * CPU as a share of the whole machine (1.0 = every core busy), so {@code 0.25} on a 14-core
     * machine is three and a half cores.
     */
    synchronized Map<String, Object> result() {
        var out = new LinkedHashMap<String, Object>();
        var cores = Runtime.getRuntime().availableProcessors();
        out.put("samples", samples);
        out.put("jvmCpuAvgCores", Stats.round(samples == 0 ? Double.NaN : processCpuSum / samples * cores));
        out.put("jvmCpuMaxCores", Stats.round(processCpuMax * cores));
        out.put("machineCpuAvgPct", Stats.round(samples == 0 ? Double.NaN : systemCpuSum / samples * 100));
        out.put("heapMaxUsedMb", heapMaxUsed / (1024 * 1024));
        return out;
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
