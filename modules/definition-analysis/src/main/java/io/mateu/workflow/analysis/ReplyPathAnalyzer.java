package io.mateu.workflow.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Proves that no run of a definition can reply twice, and that a sync-invocable one can reply at
 * all.
 *
 * <p><b>The rule.</b> Two REPLY steps may coexist only when no single process instance can run
 * both. On this engine's graph — acyclic, pure dataflow, every eligible step runs — that is true
 * only when an <em>exclusive split</em> separates them:
 * <ul>
 *   <li>a <b>CHOICE</b>, which takes exactly one branch and latches it, with the two REPLYs
 *       reachable only through different branches of it; or</li>
 *   <li>a step with an <b>{@code onTimeoutStepId}</b>: when it times out its normal successors never
 *       run (it did not complete) and only the timeout target does; when it completes the timeout
 *       target never runs.</li>
 * </ul>
 * and the split must <em>dominate</em> both REPLYs — every way into either of them goes through
 * it, or some other way in would bypass the exclusivity.
 *
 * <p>Everything else is treated as possibly concurrent: FORK branches, a plain step with several
 * successors, and links with guards — a guard reads variables, so the analysis assumes it may be
 * true. That makes the check <b>sound</b> (it never accepts a definition that can reply twice) and
 * slightly <b>incomplete</b> (guards that happen to be mutually exclusive are not recognised; the
 * author says so with a CHOICE instead).
 *
 * <p>A REPLY reachable only through a compensation is not part of the forward flow and is rejected
 * outright: the rollback's answer is the engine's failure contract, not a step.
 */
public final class ReplyPathAnalyzer {

    public static final String REPLY = "REPLY";
    private static final String CHOICE = "CHOICE";
    private static final String END = "END";
    private static final String START = "START";
    private static final String ROOT = "\u0000root";

    /**
     * One step as far as the analysis cares.
     *
     * @param id                 the step id
     * @param type               the step type name (e.g. {@code "CHOICE"})
     * @param preconditions      the ids of the steps it waits for, whichever way they were declared
     * @param onTimeoutStepId    where it routes when it times out, or null
     * @param compensationStepId the step that undoes it, or null
     */
    public record Node(String id, String type, List<String> preconditions, String onTimeoutStepId,
                       String compensationStepId) {
        public Node {
            preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        }
    }

    /** What the analysis found: errors reject the definition, warnings are reported and let through. */
    public record Report(List<String> errors, List<String> warnings) {
        public boolean isValid() {
            return errors.isEmpty();
        }
    }

    public static Report analyze(Collection<Node> nodes, boolean syncInvocationEnabled) {
        return new ReplyPathAnalyzer(nodes).run(syncInvocationEnabled);
    }

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, List<String>> successors = new HashMap<>();
    private final Set<String> compensationTargets = new HashSet<>();

    private ReplyPathAnalyzer(Collection<Node> input) {
        for (var node : input) {
            if (node.id() != null) {
                nodes.putIfAbsent(node.id(), node);
            }
        }
        for (var node : nodes.values()) {
            if (isSet(node.compensationStepId())) {
                compensationTargets.add(node.compensationStepId());
            }
        }
        for (var node : nodes.values()) {
            successors.putIfAbsent(node.id(), new ArrayList<>());
            for (var pre : node.preconditions()) {
                if (nodes.containsKey(pre)) {
                    successors.computeIfAbsent(pre, k -> new ArrayList<>()).add(node.id());
                }
            }
            if (isSet(node.onTimeoutStepId()) && nodes.containsKey(node.onTimeoutStepId())) {
                successors.computeIfAbsent(node.id(), k -> new ArrayList<>()).add(node.onTimeoutStepId());
            }
        }
        // A virtual root in front of every entry point: START, a WAIT_FOR_MESSAGE with no way in
        // (it starts waiting when the process is created), and any other step nothing leads to
        // except a compensation (which only the rollback starts).
        var roots = new ArrayList<String>();
        for (var node : nodes.values()) {
            boolean entry = START.equals(node.type())
                    || (node.preconditions().isEmpty() && !compensationTargets.contains(node.id()));
            if (entry && !isTimeoutTarget(node.id())) {
                roots.add(node.id());
            }
        }
        successors.put(ROOT, roots);
    }

    private Report run(boolean syncInvocationEnabled) {
        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        var reachable = reachableFrom(ROOT, null);
        var replies = nodes.values().stream().filter(n -> REPLY.equals(n.type())).map(Node::id).toList();

        for (var reply : replies) {
            if (compensationTargets.contains(reply)) {
                errors.add("REPLY step '" + reply + "' is a compensation step; a REPLY cannot be used"
                        + " to undo a step — the failure contract answers the caller during a rollback.");
            }
        }

        var forwardReplies = replies.stream()
                .filter(reachable::contains)
                .filter(r -> !compensationTargets.contains(r))
                .toList();
        for (int i = 0; i < forwardReplies.size(); i++) {
            for (int j = i + 1; j < forwardReplies.size(); j++) {
                var conflict = conflict(forwardReplies.get(i), forwardReplies.get(j));
                if (conflict != null) {
                    errors.add(conflict);
                }
            }
        }

        if (syncInvocationEnabled) {
            if (forwardReplies.isEmpty()) {
                errors.add("The definition is sync-invocable (syncInvocation.enabled) but no REPLY step"
                        + " is reachable, so a synchronous caller could never get an answer.");
            } else {
                // Which ends can a run reach without passing any REPLY?
                var withoutReply = reachableFrom(ROOT, Set.copyOf(forwardReplies));
                for (var id : withoutReply) {
                    if (ROOT.equals(id)) continue;
                    var node = nodes.get(id);
                    boolean sink = successors.getOrDefault(id, List.of()).isEmpty();
                    if (END.equals(node.type()) || (sink && !compensationTargets.contains(id))) {
                        warnings.add("A run can finish at '" + id + "' without passing a REPLY step;"
                                + " a synchronous caller of such a run gets outcome COMPLETED_WITHOUT_REPLY.");
                    }
                }
            }
        }
        return new Report(List.copyOf(errors), List.copyOf(warnings));
    }

    /** Why these two REPLYs could both run in one instance, or null if something rules it out. */
    private String conflict(String a, String b) {
        if (reachableFrom(a, null).contains(b) || reachableFrom(b, null).contains(a)) {
            return "REPLY steps '" + a + "' and '" + b + "' lie on the same path, so a run that"
                    + " reaches the second would reply twice. A process replies at most once.";
        }
        for (var split : exclusiveSplits()) {
            if (!dominates(split, a) || !dominates(split, b)) {
                continue;
            }
            var branchesA = branchesReaching(split, a);
            var branchesB = branchesReaching(split, b);
            if (!branchesA.isEmpty() && !branchesB.isEmpty() && disjoint(branchesA, branchesB)) {
                return null;
            }
        }
        return "REPLY steps '" + a + "' and '" + b + "' can both run in the same process instance"
                + " (they are not on different branches of a CHOICE, or of a step's timeout route,"
                + " that every way into both goes through). A process replies at most once.";
    }

    /** CHOICE steps, and steps that route somewhere else on timeout. */
    private List<String> exclusiveSplits() {
        var splits = new ArrayList<String>();
        for (var node : nodes.values()) {
            if (CHOICE.equals(node.type()) || isSet(node.onTimeoutStepId())) {
                splits.add(node.id());
            }
        }
        return splits;
    }

    /**
     * The mutually exclusive branches of {@code split} through which {@code target} is reachable.
     * A CHOICE's branches are its successors, one each; a timeout-routing step has two: all its
     * normal successors together (they run together when it completes) and its timeout target.
     */
    private Set<String> branchesReaching(String split, String target) {
        var node = nodes.get(split);
        var branches = new LinkedHashMap<String, List<String>>();
        if (CHOICE.equals(node.type())) {
            for (var successor : successors.getOrDefault(split, List.of())) {
                if (successor.equals(node.onTimeoutStepId())) continue;
                branches.put("branch:" + successor, List.of(successor));
            }
        } else {
            var normal = new ArrayList<String>();
            for (var successor : successors.getOrDefault(split, List.of())) {
                if (!successor.equals(node.onTimeoutStepId())) {
                    normal.add(successor);
                }
            }
            branches.put("completed", normal);
        }
        if (isSet(node.onTimeoutStepId()) && nodes.containsKey(node.onTimeoutStepId())) {
            branches.put("timeout", List.of(node.onTimeoutStepId()));
        }
        var result = new LinkedHashSet<String>();
        branches.forEach((label, starts) -> {
            for (var start : starts) {
                if (start.equals(target) || reachableFrom(start, null).contains(target)) {
                    result.add(label);
                    break;
                }
            }
        });
        return result;
    }

    /** Whether every way from the entry points to {@code target} goes through {@code split}. */
    private boolean dominates(String split, String target) {
        if (split.equals(target)) return true;
        return !reachableFrom(ROOT, Set.of(split)).contains(target);
    }

    /** Everything reachable from {@code start} (inclusive), never entering {@code blocked}. */
    private Set<String> reachableFrom(String start, Set<String> blocked) {
        var seen = new HashSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            var current = queue.poll();
            for (var next : successors.getOrDefault(current, List.of())) {
                if (blocked != null && blocked.contains(next)) continue;
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return seen;
    }

    private boolean isTimeoutTarget(String id) {
        return nodes.values().stream().anyMatch(n -> id.equals(n.onTimeoutStepId()));
    }

    private static boolean disjoint(Set<String> a, Set<String> b) {
        for (var x : a) {
            if (b.contains(x)) return false;
        }
        return true;
    }

    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }
}
