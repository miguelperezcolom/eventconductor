package io.mateu.workflow.domain.aggregates;

/**
 * Multi-instance: run a step once per element of a list. Carried in the definition as the step's
 * {@code forEach} block, on ACTION, USER_TASK, PROCESS, HTTP_CALL, LLM_CALL and RULE steps.
 *
 * <p>The step's execution becomes a <em>container</em>: when it starts, {@code items} is evaluated
 * once and one child execution — an <em>iteration</em> — is materialised per element. Each
 * iteration is an ordinary task of the step (its own id, retries, timeout and compensation) that
 * sees the process variables with {@code as} and {@code indexAs} laid over them; nothing it reports
 * is written to the process. The container completes when {@code completion} says so, failing as
 * soon as one iteration fails for good.
 *
 * @param items          JEXL over the process variables yielding a list (a variable holding a JSON
 *                       array or object is read as that structure here)
 * @param as             the iteration variable holding the element; default {@code item}
 * @param indexAs        the iteration variable holding the 0-based position; default {@code index}
 * @param mode           {@code parallel} (default) or {@code sequential}
 * @param maxConcurrency parallel only: how many iterations may be in flight at once; null = all
 * @param output         a process variable that receives, when the container completes, a JSON
 *                       array with one element per item, in item order ({@code null} for an
 *                       iteration that did not complete)
 * @param outputElement  JEXL over what one iteration reported (plus {@code as}/{@code indexAs})
 *                       giving its element of {@code output}; default: an object of everything it
 *                       reported
 * @param completion     {@code all} (default), {@code any}, or a JEXL condition checked after each
 *                       completed iteration — see {@link #completionCondition()}
 * @param onIterationFailure what an iteration that fails for good does to the container (see
 *                       {@link #failurePolicy()}): {@code failFast} (the default — absent means it), {@code continue}
 *                       or {@code compensateAll}. Left out of the JSON when absent, so a forEach that declares none
 *                       hashes into a definition version exactly as before.
 * @param label          JEXL over the process variables with {@code as}/{@code indexAs} laid over them, evaluated once
 *                       per iteration when it is materialised, naming it in the console and the API ("Hab. 101"
 *                       instead of "item 2"); cut to {@link #MAX_LABEL_LENGTH} characters. Absent, or evaluating to
 *                       nothing or failing: no label — never a failed iteration. Left out of the JSON when absent.
 */
public record ForEach(
        String items,
        String as,
        String indexAs,
        String mode,
        Integer maxConcurrency,
        String output,
        String outputElement,
        String completion,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String onIterationFailure,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String label
) {

    /** The longest label an iteration keeps; a longer one is cut, with an ellipsis. */
    public static final int MAX_LABEL_LENGTH = 80;

    /**
     * The first iteration that fails for good fails the container at once: what has not finished is
     * cancelled, and the saga compensates the iterations that completed. As forEach always behaved.
     */
    public static final String FAIL_FAST = "failFast";
    /**
     * A failed iteration does not stop the others: the container waits for every iteration and then
     * COMPLETES, whatever failed; {@code output} then carries one {@code {status, value | error}} object
     * per item (see {@code MultiInstance#output}), so what follows can tell which items made it.
     */
    public static final String CONTINUE = "continue";
    /**
     * All or nothing, decided at the end: the container waits for every iteration; if any failed, it fails
     * — and the saga compensates every iteration that completed — and if none did, it completes as usual.
     */
    public static final String COMPENSATE_ALL = "compensateAll";
    public static final java.util.List<String> FAILURE_POLICIES = java.util.List.of(FAIL_FAST, CONTINUE, COMPENSATE_ALL);

    /** {@link #onIterationFailure} as one of {@link #FAILURE_POLICIES} (case-insensitive), {@link #FAIL_FAST} when absent or unknown. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String failurePolicy() {
        if (onIterationFailure == null || onIterationFailure.isBlank()) {
            return FAIL_FAST;
        }
        return FAILURE_POLICIES.stream().filter(p -> p.equalsIgnoreCase(onIterationFailure.trim()))
                .findFirst().orElse(FAIL_FAST);
    }

    /** Whether a failed iteration leaves the others running ({@code continue} or {@code compensateAll}). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean waitsForEveryIteration() {
        return !FAIL_FAST.equals(failurePolicy());
    }

    public static final String DEFAULT_AS = "item";
    public static final String DEFAULT_INDEX_AS = "index";
    public static final String PARALLEL = "parallel";
    public static final String SEQUENTIAL = "sequential";
    public static final String ALL = "all";
    public static final String ANY = "any";

    /** The variable an iteration finds its element in. */
    public String itemVariable() {
        return as == null || as.isBlank() ? DEFAULT_AS : as;
    }

    /** The variable an iteration finds its 0-based position in. */
    public String indexVariable() {
        return indexAs == null || indexAs.isBlank() ? DEFAULT_INDEX_AS : indexAs;
    }

    public boolean sequential() {
        return SEQUENTIAL.equalsIgnoreCase(mode);
    }

    /** How many iterations may be in flight at once; {@link Integer#MAX_VALUE} for no limit. */
    public int concurrency() {
        if (sequential()) {
            return 1;
        }
        return maxConcurrency == null || maxConcurrency <= 0 ? Integer.MAX_VALUE : maxConcurrency;
    }

    /** Whether the container completes with its first completed iteration. */
    public boolean completesOnAny() {
        return ANY.equalsIgnoreCase(trimmedCompletion());
    }

    /**
     * The early-completion condition, or null when the container waits for every iteration
     * ({@code all}) or the first one ({@code any}).
     */
    public String completionCondition() {
        var c = trimmedCompletion();
        if (c == null || c.isEmpty() || ALL.equalsIgnoreCase(c) || ANY.equalsIgnoreCase(c)) {
            return null;
        }
        return c;
    }

    public boolean hasOutput() {
        return output != null && !output.isBlank();
    }

    private String trimmedCompletion() {
        return completion == null ? null : completion.trim();
    }

    /**
     * The settings a compensation over a multi-instance or looped step runs with: one iteration per
     * unit of work to undo, one at a time, every one of them.
     */
    public static ForEach compensation() {
        return new ForEach(null, null, null, SEQUENTIAL, 1, null, null, ALL, null, null);
    }
}
