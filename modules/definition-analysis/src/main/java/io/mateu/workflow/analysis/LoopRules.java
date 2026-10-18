package io.mateu.workflow.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules of the three ways a step runs more than once — {@code forEach} (multi-instance),
 * {@code repeat} (one step until a condition) and {@code loopBack} (an explicit back-edge re-running
 * a section) — on their generic (parsed JSON/YAML) form, shared as code by the engine and the Maven
 * plugin. The JEXL in them is checked separately, by whoever holds a JEXL engine.
 *
 * <p>The precondition graph stays acyclic: a {@code loopBack} is not a precondition, so the cycle
 * check never sees it and an ordinary cycle is still an error. What this adds is the shape a loop
 * must have to mean one thing at runtime: its target upstream of it, its section free of steps that
 * must not run twice, and two loops either nested or apart, never crossing.
 */
public final class LoopRules {

    /** The step types a {@code forEach} or a {@code repeat} may be declared on: the ones that do work. */
    public static final Set<String> REPEATABLE_TYPES =
            Set.of("ACTION", "USER_TASK", "PROCESS", "HTTP_CALL", "LLM_CALL", "RULE");

    /** The step types that may not carry a {@code loopBack}. */
    private static final Set<String> NO_LOOP_BACK_TYPES = Set.of("START", "END", "REPLY");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * One step as far as these rules care.
     *
     * @param id                 the step id
     * @param type               the step type name
     * @param preconditions      the ids of the steps it waits for, whichever way they were declared
     * @param compensationStepId the step that undoes it, or null
     * @param forEach            its {@code forEach} block as a map, or null
     * @param repeat             its {@code repeat} block as a map, or null
     * @param loopBack           its {@code loopBack} block as a map, or null
     */
    public record Node(String id, String type, List<String> preconditions, String compensationStepId,
                       Map<?, ?> forEach, Map<?, ?> repeat, Map<?, ?> loopBack) {
        public Node {
            preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        }
    }

    /** What the rules found: errors reject the definition, warnings are reported and let through. */
    public record Report(List<String> errors, List<String> warnings) {
        public boolean isValid() {
            return errors.isEmpty();
        }
    }

    /**
     * Checks every loop and multi-instance declaration in a definition. Assumes the precondition
     * graph is acyclic and its references resolve — run it after those checks.
     */
    public static Report analyze(Collection<Node> input) {
        var nodes = new LinkedHashMap<String, Node>();
        for (var node : input) {
            if (node != null && node.id() != null) {
                nodes.put(node.id(), node);
            }
        }
        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        var compensationTargets = new LinkedHashSet<String>();
        nodes.values().forEach(node -> {
            if (set(node.compensationStepId())) {
                compensationTargets.add(node.compensationStepId());
            }
        });
        var successors = successorsOf(nodes.values());

        for (var node : nodes.values()) {
            var where = "Step '" + node.id() + "'";
            if (node.forEach() != null) {
                errors.addAll(forEachProblems(node, where));
            }
            if (node.repeat() != null) {
                errors.addAll(repeatProblems(node, where));
            }
            if (node.forEach() != null && node.repeat() != null) {
                errors.add(where + " declares both forEach and repeat; a step may declare one of them.");
            }
            if ((node.forEach() != null || node.repeat() != null || node.loopBack() != null)
                    && compensationTargets.contains(node.id())) {
                errors.add(where + " is a compensation and declares "
                        + (node.forEach() != null ? "forEach" : node.repeat() != null ? "repeat" : "loopBack")
                        + "; a compensation runs once per unit of work it undoes, which the engine decides.");
            }
        }

        // Back-edges: each one's section, then how the sections sit against each other.
        var sections = new LinkedHashMap<String, Set<String>>();
        for (var node : nodes.values()) {
            if (node.loopBack() == null) {
                continue;
            }
            var where = "Step '" + node.id() + "' loopBack";
            var problems = loopBackShapeProblems(node, where);
            var to = string(node.loopBack().get("to"));
            if (problems.isEmpty()) {
                if (!nodes.containsKey(to)) {
                    problems.add(where + " goes back to unknown step '" + to + "'.");
                } else if (to.equals(node.id())) {
                    problems.add(where + " goes back to the step itself; use repeat to run one step again.");
                } else {
                    var section = section(to, node.id(), successors, nodes);
                    if (section.isEmpty()) {
                        problems.add(where + " goes back to '" + to + "', which is not upstream of it — a loop"
                                + " can only go back to a step the flow passes through on its way here.");
                    } else {
                        for (var member : section) {
                            var memberNode = nodes.get(member);
                            if ("REPLY".equals(memberNode.type())) {
                                problems.add(where + " would run REPLY step '" + member + "' again, and a process"
                                        + " replies at most once — move the REPLY out of the loop.");
                            }
                            if ("END".equals(memberNode.type())) {
                                problems.add(where + " would run END step '" + member + "' again.");
                            }
                        }
                        if (problems.isEmpty()) {
                            sections.put(node.id(), section);
                        }
                    }
                }
            }
            errors.addAll(problems);
        }
        var loopIds = new ArrayList<>(sections.keySet());
        for (int i = 0; i < loopIds.size(); i++) {
            for (int j = i + 1; j < loopIds.size(); j++) {
                var a = sections.get(loopIds.get(i));
                var b = sections.get(loopIds.get(j));
                boolean overlap = a.stream().anyMatch(b::contains);
                if (overlap && !a.containsAll(b) && !b.containsAll(a)) {
                    errors.add("The loops of steps '" + loopIds.get(i) + "' and '" + loopIds.get(j)
                            + "' cross: their sections share steps without one containing the other. Loops"
                            + " must be nested or apart.");
                }
            }
        }

        // A REPLY that may run more than once.
        for (var node : nodes.values()) {
            if ("REPLY".equals(node.type()) && node.repeat() != null) {
                errors.add("Step '" + node.id() + "' is a REPLY with a repeat; a process replies at most once.");
            }
        }

        // A cap below the loop's own bound is legal — the lower one wins — but worth saying.
        for (var entry : sections.entrySet()) {
            var max = integer(nodes.get(entry.getKey()).loopBack().get("maxIterations"));
            if (max == null) {
                continue;
            }
            for (var member : entry.getValue()) {
                var forEachOf = nodes.get(member);
                if (forEachOf.forEach() == null && forEachOf.repeat() == null) {
                    continue;
                }
                if (forEachOf.repeat() != null) {
                    warnings.add("Step '" + member + "' repeats inside the loop of step '" + entry.getKey()
                            + "': it may run up to " + max + " × its own maxIterations times.");
                }
            }
        }
        return new Report(errors, warnings);
    }

    /**
     * The section of a back-edge from {@code from} to {@code to}: every step on some path from
     * {@code to} down to {@code from}, both included — forward-reachable from {@code to} and
     * backward-reachable from {@code from}. Empty when {@code to} is not upstream of {@code from}.
     * The engine computes the section it re-opens with this same method.
     */
    public static Set<String> section(String to, String from, Map<String, List<String>> successors,
                                      Map<String, ?> known) {
        var forward = reach(to, successors);
        if (!forward.contains(from)) {
            return Set.of();
        }
        var predecessors = new HashMap<String, List<String>>();
        successors.forEach((step, next) -> next.forEach(n ->
                predecessors.computeIfAbsent(n, k -> new ArrayList<>()).add(step)));
        var backward = reach(from, predecessors);
        var section = new LinkedHashSet<String>();
        for (var step : forward) {
            if (backward.contains(step) && (known == null || known.containsKey(step))) {
                section.add(step);
            }
        }
        return section;
    }

    /** Successor lists (step → the steps that wait for it), built from each step's preconditions. */
    public static Map<String, List<String>> successorsOf(Collection<Node> nodes) {
        var successors = new LinkedHashMap<String, List<String>>();
        for (var node : nodes) {
            successors.computeIfAbsent(node.id(), k -> new ArrayList<>());
            for (var pre : node.preconditions()) {
                successors.computeIfAbsent(pre, k -> new ArrayList<>()).add(node.id());
            }
        }
        return successors;
    }

    private static Set<String> reach(String start, Map<String, List<String>> edges) {
        var seen = new LinkedHashSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add(start);
        while (!queue.isEmpty()) {
            var next = queue.poll();
            if (seen.add(next)) {
                queue.addAll(edges.getOrDefault(next, List.of()));
            }
        }
        return seen;
    }

    private static List<String> forEachProblems(Node node, String where) {
        var problems = new ArrayList<String>();
        var forEach = node.forEach();
        where = where + " forEach";
        if (node.type() != null && !REPEATABLE_TYPES.contains(node.type())) {
            problems.add("Step '" + node.id() + "' declares forEach, but a " + node.type()
                    + " step cannot run once per item; forEach is for " + String.join(", ", sorted(REPEATABLE_TYPES)) + ".");
        }
        if (!set(forEach.get("items"))) {
            problems.add(where + " must define items: an expression giving the list to run over.");
        }
        var mode = string(forEach.get("mode"));
        if (set(mode) && !"parallel".equalsIgnoreCase(mode) && !"sequential".equalsIgnoreCase(mode)) {
            problems.add(where + " mode '" + mode + "' is not parallel or sequential.");
        }
        if (forEach.get("maxConcurrency") != null) {
            var max = integer(forEach.get("maxConcurrency"));
            if (max == null || max < 1) {
                problems.add(where + " maxConcurrency must be a whole number of at least 1.");
            } else if ("sequential".equalsIgnoreCase(mode)) {
                problems.add(where + " maxConcurrency is for parallel mode; a sequential forEach runs one at a time.");
            }
        }
        var as = string(forEach.get("as"));
        var indexAs = string(forEach.get("indexAs"));
        if (set(as) && !IDENTIFIER.matcher(as).matches()) {
            problems.add(where + " as '" + as + "' is not a valid variable name.");
        }
        if (set(indexAs) && !IDENTIFIER.matcher(indexAs).matches()) {
            problems.add(where + " indexAs '" + indexAs + "' is not a valid variable name.");
        }
        var item = set(as) ? as : "item";
        var index = set(indexAs) ? indexAs : "index";
        if (item.equals(index)) {
            problems.add(where + " as and indexAs are both '" + item + "'; they must differ.");
        }
        var output = string(forEach.get("output"));
        if (set(output) && !IDENTIFIER.matcher(output).matches()) {
            problems.add(where + " output '" + output + "' is not a valid variable name.");
        }
        if (set(string(forEach.get("outputElement"))) && !set(output)) {
            problems.add(where + " outputElement needs output: the variable the elements are collected into.");
        }
        return problems;
    }

    private static List<String> repeatProblems(Node node, String where) {
        var problems = new ArrayList<String>();
        var repeat = node.repeat();
        if (node.type() != null && !REPEATABLE_TYPES.contains(node.type())) {
            problems.add("Step '" + node.id() + "' declares repeat, but a " + node.type()
                    + " step cannot be repeated; repeat is for " + String.join(", ", sorted(REPEATABLE_TYPES)) + ".");
        }
        where = where + " repeat";
        if (!set(string(repeat.get("until")))) {
            problems.add(where + " must define until: the condition that ends the repetition.");
        }
        problems.addAll(boundProblems(repeat, where));
        return problems;
    }

    private static List<String> loopBackShapeProblems(Node node, String where) {
        var problems = new ArrayList<String>();
        if (node.type() != null && NO_LOOP_BACK_TYPES.contains(node.type())) {
            problems.add("Step '" + node.id() + "' is a " + node.type() + " and cannot carry a loopBack.");
        }
        if (!set(string(node.loopBack().get("to")))) {
            problems.add(where + " must define to: the step the loop goes back to.");
        }
        problems.addAll(boundProblems(node.loopBack(), where));
        return problems;
    }

    /** maxIterations (required, ≥ 1) and delay (≥ 0) — the same for repeat and loopBack. */
    private static List<String> boundProblems(Map<?, ?> block, String where) {
        var problems = new ArrayList<String>();
        var max = integer(block.get("maxIterations"));
        if (block.get("maxIterations") == null) {
            problems.add(where + " must define maxIterations: the most times it may run, the first included.");
        } else if (max == null || max < 1) {
            problems.add(where + " maxIterations must be a whole number of at least 1.");
        }
        var delay = block.get("delay");
        if (delay instanceof Number n && n.longValue() < 0) {
            problems.add(where + " delay cannot be negative.");
        } else if (delay instanceof String s && !s.isBlank()) {
            try {
                if (java.time.Duration.parse(s.trim()).isNegative()) {
                    problems.add(where + " delay cannot be negative.");
                }
            } catch (java.time.format.DateTimeParseException e) {
                problems.add(where + " delay '" + s + "' is not milliseconds or an ISO-8601 duration (e.g. PT30S).");
            }
        }
        return problems;
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static boolean set(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer integer(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Long l && l <= Integer.MAX_VALUE && l >= Integer.MIN_VALUE) {
            return l.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private LoopRules() {
    }
}
