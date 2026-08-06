package io.mateu.workflowbench.soak;

import io.mateu.workflow.dtos.Variable;

import java.util.List;

/**
 * The realistic reliability suite: a weighted mix of definitions, with a configured fraction of
 * processes deliberately steered to fail so the retry, saga-compensation and
 * {@code COMPENSATION_FAILED} paths are exercised at volume — the paths a happy-path throughput run
 * never touches.
 *
 * <p>Every choice is a pure, decorrelated function of the arrival index {@code i}, so the run is
 * reproducible and the verifier can predict the terminal status of every process from its business
 * key alone (no shared randomness):
 *
 * <ul>
 *   <li>{@code <prefix>-order-saga-ok-<i>}     → COMPLETED</li>
 *   <li>{@code <prefix>-order-saga-comp-<i>}   → COMPENSATED (risky step fails, retries exhaust,
 *       compensations succeed)</li>
 *   <li>{@code <prefix>-order-saga-compfail-<i>} → COMPENSATION_FAILED (a compensation also fails)</li>
 *   <li>{@code <prefix>-linear-<i>}            → COMPLETED</li>
 * </ul>
 *
 * The same intent reaches the worker as a {@code benchOutcome} variable; see {@code BenchmarkWorkerApp}.
 */
public final class ScaleWorkload implements Workload {

    private static final String SAGA = "order-saga";
    private static final String LINEAR = "bench-3-steps";

    // Knuth multiplicative hash — spreads consecutive indices across the whole 64-bit range so the
    // two buckets drawn from it (definition, outcome) are effectively independent.
    private static final long MIX = 0x9E3779B97F4A7C15L;

    private final int sagaWeightPct;
    private final int compPermil;
    private final int compFailPermil;

    public ScaleWorkload(int sagaWeightPct, int compPermil, int compFailPermil) {
        this.sagaWeightPct = clamp(sagaWeightPct, 0, 100);
        this.compPermil = clamp(compPermil, 0, 1000);
        // A compensation can only fail among the processes that roll back at all.
        this.compFailPermil = clamp(compFailPermil, 0, this.compPermil);
    }

    @Override
    public List<String> definitions() {
        return List.of("/scale/order-saga.json", "/workflows/bench-3-steps.json");
    }

    @Override
    public Creation at(String prefix, long i) {
        long h = i * MIX;
        boolean saga = Math.floorMod(h, 100) < sagaWeightPct;
        if (!saga) {
            return Creation.of(LINEAR, prefix + "-linear-" + i);
        }
        int outcomeBucket = (int) Math.floorMod(h >>> 20, 1000);
        String outcome = outcomeBucket >= compPermil ? "ok"
                : outcomeBucket < compFailPermil ? "compfail"
                : "comp";
        return new Creation(SAGA,
                prefix + "-order-saga-" + outcome + "-" + i,
                List.of(new Variable("benchOutcome", outcome)));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
