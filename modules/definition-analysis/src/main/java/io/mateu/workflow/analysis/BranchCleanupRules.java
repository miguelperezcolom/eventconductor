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
 *   <li>{@code compensateAbandoned} only on an END — the only step that cuts other branches short.</li>
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
        var violations = new ArrayList<String>();
        if (Boolean.TRUE.equals(compensateLosers) && !("JOIN".equals(type) && "XOR".equals(joinType))) {
            violations.add(where + ": compensateLosers is only allowed on a JOIN with joinType XOR"
                    + " (it compensates the branches the join did not go on with).");
        }
        if (Boolean.TRUE.equals(compensateAbandoned) && !"END".equals(type)) {
            violations.add(where + ": compensateAbandoned is only allowed on an END"
                    + " (it compensates the branches the END cuts short).");
        }
        return violations;
    }
}
