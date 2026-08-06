package io.mateu.workflowbench.soak;

import io.mateu.workflow.dtos.Variable;

import java.util.List;

/**
 * What the load driver should create for each arrival — decoupled from the driver so the same
 * durable-accounting soak can run either the single-definition throughput control or the realistic
 * multi-definition suite with injected failures.
 *
 * <p>The key idea for reconciliation: a workload's intent must be recoverable from the database
 * alone, with no shared randomness between the driver, the worker and the verifier. So the intended
 * outcome is encoded in <b>the business key</b> (which the verifier reads) and, for the worker, in a
 * {@code benchOutcome} variable — never in a hash the three would have to compute identically.
 */
public interface Workload {

    /** Classpath resources of the definitions this workload uses; installed before load starts. */
    List<String> definitions();

    /** What to create for arrival {@code i} of a run namespaced by {@code prefix}. */
    Creation at(String prefix, long i);

    record Creation(String definitionId, String businessKey, List<Variable> variables) {
        public static Creation of(String definitionId, String businessKey) {
            return new Creation(definitionId, businessKey, List.of());
        }
    }

    /**
     * The original soak: one linear 3-ACTION definition, business key {@code <prefix>-<i>}, no
     * variables. Preserves the exact key shape {@code invariants.sql} already reconciles on.
     */
    static Workload linear() {
        return new Workload() {
            @Override
            public List<String> definitions() {
                return List.of("/workflows/bench-3-steps.json");
            }

            @Override
            public Creation at(String prefix, long i) {
                return Creation.of("bench-3-steps", prefix + "-" + i);
            }
        };
    }
}
