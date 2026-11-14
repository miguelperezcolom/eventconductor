package io.mateu.workflow.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the opt-ins to clean up after abandoned branches may be declared — one rule, shared by the
 * engine (when it loads a definition) and {@code eventconductor:validate} (at build time):
 *
 * <ul>
 *   <li>{@code compensateLosers} only on a JOIN with {@code joinType: XOR} — the only step that goes on
 *       with one branch and leaves others behind;</li>
 *   <li>{@code compensateAbandoned} only on an END — the only step that cuts other branches short;</li>
 *   <li>{@code compensate} only on an END — it ends the process by rolling it back — and never beside
 *       {@code compensateAbandoned}, whose cleanup it already includes: a rollback compensates every
 *       completed compensable step, on every branch.</li>
 * </ul>
 *
 * A flag set to {@code true} anywhere else is an error rather than a silent no-op: it says the author
 * expects a cleanup that would never happen.
 */
public final class BranchCleanupRules {

    private BranchCleanupRules() {
    }

    /**
     * @param type      the step's type, as written
     * @param joinType  its joinType, as written (null means AND)
     * @param where     how to name the step in a message, e.g. {@code step 'pay'}
     */
    public static List<String> stepViolations(String type, String joinType, Boolean compensateLosers,
                                              Boolean compensateAbandoned, String where) {
        return stepViolations(type, joinType, compensateLosers, compensateAbandoned, null, where);
    }

    /**
     * @param compensate the END's {@code compensate}, as written (null means false)
     */
    public static List<String> stepViolations(String type, String joinType, Boolean compensateLosers,
                                              Boolean compensateAbandoned, Boolean compensate, String where) {
        var violations = new ArrayList<String>();
        if (Boolean.TRUE.equals(compensateLosers) && !("JOIN".equals(type) && "XOR".equals(joinType))) {
            violations.add(where + ": compensateLosers is only allowed on a JOIN with joinType XOR"
                    + " (it compensates the branches the join did not go on with).");
        }
        if (Boolean.TRUE.equals(compensateAbandoned) && !"END".equals(type)) {
            violations.add(where + ": compensateAbandoned is only allowed on an END"
                    + " (it compensates the branches the END cuts short).");
        }
        if (Boolean.TRUE.equals(compensate) && !"END".equals(type)) {
            violations.add(where + ": compensate is only allowed on an END"
                    + " (it ends the process by compensating what it completed).");
        }
        if (Boolean.TRUE.equals(compensate) && Boolean.TRUE.equals(compensateAbandoned)) {
            violations.add(where + ": compensate and compensateAbandoned cannot both be set"
                    + " (compensate already compensates every completed compensable step, on every branch).");
        }
        return violations;
    }
}
