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
 */
public record ForEach(
        String items,
        String as,
        String indexAs,
        String mode,
        Integer maxConcurrency,
        String output,
        String outputElement,
        String completion
) {

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
        return new ForEach(null, null, null, SEQUENTIAL, 1, null, null, ALL);
    }
}
