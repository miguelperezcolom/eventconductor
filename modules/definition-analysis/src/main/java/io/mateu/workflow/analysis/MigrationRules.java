package io.mateu.workflow.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The rules of moving a running process onto another version of its definition — which step of the
 * old version becomes which step of the new one, what happens to each step's execution, and when the
 * move is refused — on the definitions' generic (parsed JSON/YAML) form, shared as code by the engine
 * (the migration itself and its dry run) and the Maven plugin (the {@code check-migration} goal in CI).
 *
 * <h2>The mapping</h2>
 *
 * <p>Source step id → target step id. By default a step maps onto the step of the same id when the
 * target has one of a {@linkplain #compatible compatible type}; an explicit entry overrides that, and an
 * explicit blank target says "no counterpart".
 *
 * <h2>Two checks</h2>
 *
 * <ul>
 *   <li>{@link #plan} — one process: its steps' states (the engine's facts) against the two versions.
 *       Gives every step an {@link Action} and the problems; any {@link Severity#ERROR} refuses it.</li>
 *   <li>{@link #checkDefinitions} — no process: which steps of the old version a process could be
 *       waiting in and have no compatible counterpart. What CI runs before a version ships.</li>
 * </ul>
 *
 * <p>See {@code INSTANCE-MIGRATION-PLAN.md} for the table of semantics this implements.
 */
public final class MigrationRules {

    private MigrationRules() {
    }

    /** Problems refuse a migration (ERROR) or are reported and let through (WARNING). */
    public enum Severity { ERROR, WARNING }

    /** One problem, about a step ({@code stepId}) or about the whole process (null). */
    public record Problem(Severity severity, String stepId, String message) {
        @Override
        public String toString() {
            return severity + (stepId == null ? "" : " [" + stepId + "]") + ": " + message;
        }
    }

    /** The state of a step's current execution, as far as migrating it goes. */
    public enum State {
        /** Never left the starting gate. */
        NOT_STARTED,
        /** Started and not finished: at a worker, waiting for a timer, a message, a lock, a retry, its iterations. */
        ACTIVE,
        /** Finished successfully. */
        COMPLETED,
        /** Finished without running: a branch not taken, a timeout routed elsewhere. */
        SKIPPED,
        /** Failed and holding the flow: ERROR, or a TIMEOUT with no route. */
        FAILED
    }

    /** What the migration does with one step. */
    public enum Action {
        /** Not active: the execution takes the target step's id and frozen copy, its history kept. */
        REMAP,
        /** At a worker, task contract unchanged: remapped, the task left running — its answer still lands. */
        KEEP_TASK,
        /** At a worker, task contract changed: the task is cancelled and dispatched again under the target. */
        REDISPATCH,
        /** Waiting (timer, message, retry backoff, lock queue, iterations): remapped, deadline and subscription recomputed. */
        REARM,
        /** Finished, no counterpart: kept as history, out of the flow. */
        HISTORY,
        /** Not started, no counterpart: cancelled quietly, kept as history. */
        DROP,
        /** Injected at runtime by a DYNAMIC step: neither version knows it; carried over as it is. */
        CARRY,
        /** A target step with no source: a new execution, run when its preconditions hold. */
        ADD,
        /** A target step with no source, upstream of a step the process already reached: recorded as passed. */
        ADD_PASSED
    }

    /**
     * What the engine knows of one step's current execution.
     *
     * @param atWorker      in flight at a worker (dispatched, not answered) — kept or re-dispatched
     * @param waitingOnLock queued at a LOCK
     * @param holdsLock     a completed LOCK whose critical section is still open (no UNLOCK yet)
     * @param injected      materialised at runtime by a DYNAMIC step
     * @param escalationsFired for a USER_TASK, how many of its escalation levels already fired
     */
    public record StepState(String stepId, State state, boolean atWorker, boolean waitingOnLock, boolean holdsLock,
                            boolean injected, int escalationsFired) {
        public StepState {
            state = state == null ? State.NOT_STARTED : state;
        }

        public StepState(String stepId, State state, boolean atWorker, boolean waitingOnLock, boolean holdsLock,
                         boolean injected) {
            this(stepId, state, atWorker, waitingOnLock, holdsLock, injected, 0);
        }

        public StepState(String stepId, State state) {
            this(stepId, state, false, false, false, false, 0);
        }
    }

    /**
     * What the engine knows of the process.
     *
     * @param status            its status name
     * @param rollbackUnderway  a saga rollback has a compensation to run or in flight
     * @param holdsProcessLock  it holds (or queues for) its definition's {@code processLock}
     * @param variables         the names of the variables it has
     */
    public record ProcessFacts(String status, boolean rollbackUnderway, boolean holdsProcessLock, Set<String> variables) {
        public ProcessFacts {
            variables = variables == null ? Set.of() : Set.copyOf(variables);
        }
    }

    /**
     * Everything a plan is made from.
     *
     * @param source            the steps of the version the process runs (generic form)
     * @param sourceProcessLock its {@code processLock} block, or null
     * @param target            the steps of the version to move to
     * @param targetProcessLock its {@code processLock} block, or null
     * @param stepMapping       explicit source → target entries (a blank target: no counterpart)
     * @param variableMapping   variables the migration sets (name → value)
     * @param states            the state of each current execution
     */
    public record Input(List<? extends Map<?, ?>> source, Map<?, ?> sourceProcessLock,
                        List<? extends Map<?, ?>> target, Map<?, ?> targetProcessLock,
                        Map<String, String> stepMapping, Map<String, String> variableMapping,
                        List<StepState> states, ProcessFacts facts) {
        public Input {
            source = source == null ? List.of() : source;
            target = target == null ? List.of() : target;
            stepMapping = stepMapping == null ? Map.of() : stepMapping;
            variableMapping = variableMapping == null ? Map.of() : variableMapping;
            states = states == null ? List.of() : states;
            facts = facts == null ? new ProcessFacts(null, false, false, Set.of()) : facts;
        }
    }

    /** One step's fate: its source and target ids (either may be null), its state and its action. */
    public record StepPlan(String sourceStepId, String targetStepId, State state, Action action, String note) {
    }

    /** The plan: the effective mapping, every step's fate, and the problems. */
    public record Plan(Map<String, String> mapping, List<StepPlan> steps, List<Problem> problems) {
        /** Whether nothing refuses it. */
        public boolean executable() {
            return errors().isEmpty();
        }

        public List<Problem> errors() {
            return problems.stream().filter(p -> p.severity() == Severity.ERROR).toList();
        }

        public List<Problem> warnings() {
            return problems.stream().filter(p -> p.severity() == Severity.WARNING).toList();
        }

        /** The fate of a source step, or null. */
        public StepPlan ofSource(String sourceStepId) {
            return steps.stream().filter(s -> Objects.equals(sourceStepId, s.sourceStepId())).findFirst().orElse(null);
        }
    }

    // ── Types ─────────────────────────────────────────────────────────────────────────────────────

    /** The step types whose work is a task sent to a worker: one may stand in for another. */
    public static final Set<String> TASK_TYPES = Set.of("ACTION", "HTTP_CALL", "LLM_CALL", "RULE");

    /** The step types that dispatch something to someone outside the engine while they run. */
    public static final Set<String> DISPATCHING_TYPES =
            Set.of("ACTION", "HTTP_CALL", "LLM_CALL", "RULE", "USER_TASK", "PROCESS");

    /** Step types that complete in the step-over that starts them: no process is ever left waiting in one. */
    public static final Set<String> INSTANT_TYPES =
            Set.of("START", "END", "FORK", "JOIN", "CHOICE", "REPLY", "SEND_MESSAGE", "UNLOCK");

    /** The fields that say what a worker is asked to do; a change to any of them re-dispatches a task in flight. */
    public static final List<String> CONTRACT_FIELDS =
            List.of("type", "topic", "task", "formId", "ruleId", "childWorkflowDefinitionId", "http", "llm");

    /** Whether a step of {@code from}'s type can become one of {@code to}'s: the same type, or both worker tasks. */
    public static boolean compatible(String from, String to) {
        var a = normalisedType(from);
        var b = normalisedType(to);
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b) || (TASK_TYPES.contains(a) && TASK_TYPES.contains(b));
    }

    /** Whether two steps ask a worker for the same thing (see {@link #CONTRACT_FIELDS}). */
    public static boolean sameContract(Map<?, ?> a, Map<?, ?> b) {
        for (var field : CONTRACT_FIELDS) {
            var x = "type".equals(field) ? normalisedType(text(a, field)) : blankToNull(a.get(field));
            var y = "type".equals(field) ? normalisedType(text(b, field)) : blankToNull(b.get(field));
            if (!Objects.equals(x, y)) {
                return false;
            }
        }
        return true;
    }

    /** Whether a process can be left waiting in a step of this shape (anything but an instantaneous one). */
    public static boolean waitable(Map<?, ?> step) {
        var type = normalisedType(text(step, "type"));
        if (type == null || INSTANT_TYPES.contains(type)) {
            return false;
        }
        return !"PUBLISH_EVENT".equals(type) || step.get("awaitReply") != null;
    }

    // ── Plan ─────────────────────────────────────────────────────────────────────────────────────

    /** The plan for one process. */
    public static Plan plan(Input in) {
        var problems = new ArrayList<Problem>();
        var facts = in.facts();
        if (facts.status() == null || !Set.of("RUNNING", "PAUSED", "ERROR").contains(facts.status())) {
            problems.add(error(null, "only a RUNNING, PAUSED or ERROR process is migrated; this one is " + facts.status()));
        }
        if (facts.rollbackUnderway()) {
            problems.add(error(null, "its saga rollback is under way"));
        }
        var source = nodes(in.source());
        var target = nodes(in.target());
        var states = new LinkedHashMap<String, StepState>();
        in.states().forEach(s -> states.putIfAbsent(s.stepId(), s));

        // The source steps: the definition's, plus any the process has that its snapshot does not name.
        var sourceIds = new LinkedHashSet<String>();
        source.keySet().forEach(sourceIds::add);
        states.values().stream().filter(s -> !s.injected()).map(StepState::stepId).forEach(sourceIds::add);
        var injected = states.values().stream().filter(StepState::injected).map(StepState::stepId)
                .filter(id -> !source.containsKey(id)).toList();

        // A compensation that has run means the rollback started: what it undid must not be re-planned.
        source.values().stream().map(n -> n.compensationStepId).filter(Objects::nonNull).distinct()
                .filter(id -> stateOf(states, id) != State.NOT_STARTED)
                .forEach(id -> problems.add(error(id, "compensation step '" + id + "' has already run: a saga that"
                        + " started rolling back is not migrated")));

        var mapping = mapping(sourceIds, source, target, in.stepMapping(), injected, problems);

        var steps = new ArrayList<StepPlan>();
        var reachedTargets = new LinkedHashSet<String>();
        var doneTargets = new LinkedHashSet<String>();
        for (var id : sourceIds) {
            var state = stateOf(states, id);
            var runtime = states.get(id);
            var to = mapping.get(id);
            var from = source.get(id);
            var into = to == null ? null : target.get(to);
            if (to != null && state != State.NOT_STARTED) {
                reachedTargets.add(to);
            }
            if (to != null && (state == State.COMPLETED || state == State.SKIPPED)) {
                doneTargets.add(to);
            }
            steps.add(fate(id, to, state, runtime, from, into, target, problems));
        }
        injected.forEach(id -> steps.add(new StepPlan(id, id, stateOf(states, id), Action.CARRY,
                "injected at runtime; carried over as it is")));

        // Target steps nobody maps onto.
        var mappedTargets = new LinkedHashSet<>(mapping.values());
        var successors = successors(target.values());
        for (var node : target.values()) {
            if (mappedTargets.contains(node.id)) {
                continue;
            }
            var downstream = reach(node.id, successors);
            downstream.remove(node.id);
            var passed = downstream.stream().filter(reachedTargets::contains).findFirst().orElse(null);
            if (passed != null) {
                doneTargets.add(node.id);
                steps.add(new StepPlan(null, node.id, null, Action.ADD_PASSED,
                        "new, upstream of '" + passed + "' which the process already reached: recorded as passed"));
                problems.add(warning(node.id, "new step '" + node.id + "' is upstream of '" + passed
                        + "', which the process already reached: it is recorded as passed and does not run"));
            } else {
                steps.add(new StepPlan(null, node.id, null, Action.ADD, "new: runs when its preconditions hold"));
            }
        }

        checkPreconditions(steps, target, doneTargets, problems);
        checkVariables(steps, target, doneTargets, facts, in.variableMapping(), problems);
        checkProcessLock(in.sourceProcessLock(), in.targetProcessLock(), facts, problems);
        return new Plan(mapping, List.copyOf(steps), List.copyOf(problems));
    }

    /** The effective mapping, with the problems of the explicit one. Unmapped source steps are absent. */
    private static Map<String, String> mapping(Collection<String> sourceIds, Map<String, Node> source,
                                               Map<String, Node> target, Map<String, String> explicit,
                                               List<String> injected, List<Problem> problems) {
        explicit.forEach((from, to) -> {
            if (injected.contains(from)) {
                problems.add(error(from, "step '" + from + "' was injected at runtime and is carried over as it is;"
                        + " it cannot be mapped"));
            } else if (!sourceIds.contains(from)) {
                problems.add(error(from, "the mapping names source step '" + from + "', which the process does not have"));
            }
            if (to != null && !to.isBlank() && !target.containsKey(to.strip())) {
                problems.add(error(from, "the mapping sends '" + from + "' to '" + to.strip()
                        + "', which the target version does not have"));
            }
        });
        var mapping = new LinkedHashMap<String, String>();
        for (var id : sourceIds) {
            var from = source.get(id);
            if (explicit.containsKey(id)) {
                var to = explicit.get(id);
                if (to == null || to.isBlank() || !target.containsKey(to.strip())) {
                    continue;
                }
                to = to.strip();
                if (from != null && !compatible(from.type, target.get(to).type)) {
                    problems.add(error(id, "step '" + id + "' (" + from.type + ") cannot become '" + to + "' ("
                            + target.get(to).type + "): the types are not compatible"));
                    continue;
                }
                mapping.put(id, to);
            } else if (target.containsKey(id) && (from == null || compatible(from.type, target.get(id).type))) {
                mapping.put(id, id);
            }
        }
        var bySource = new LinkedHashMap<String, List<String>>();
        mapping.forEach((from, to) -> bySource.computeIfAbsent(to, k -> new ArrayList<>()).add(from));
        bySource.forEach((to, froms) -> {
            if (froms.size() > 1) {
                problems.add(error(to, "steps " + froms + " are all mapped onto '" + to
                        + "': a target step takes at most one source step"));
            }
        });
        return mapping;
    }

    private static StepPlan fate(String id, String to, State state, StepState runtime, Node from, Node into,
                                 Map<String, Node> target, List<Problem> problems) {
        if (to == null) {
            var hint = from != null && target.containsKey(id)
                    ? " (the target's '" + id + "' is a " + target.get(id).type + ", not compatible with " + from.type
                    + "; map it explicitly)" : "";
            switch (state) {
                case NOT_STARTED -> {
                    return new StepPlan(id, null, state, Action.DROP, "not started, no counterpart" + hint);
                }
                case COMPLETED, SKIPPED -> {
                    if (from != null && from.compensable && from.compensationStepId != null) {
                        problems.add(warning(id, "completed compensable step '" + id + "' has no counterpart: a later"
                                + " rollback will not compensate it"));
                    }
                    return new StepPlan(id, null, state, Action.HISTORY, "no counterpart: kept as history" + hint);
                }
                default -> {
                    problems.add(error(id, "step '" + id + "' is " + state + " and has no counterpart in the target"
                            + " version" + hint + "; map it to a step of the target"));
                    return new StepPlan(id, null, state, null, "no counterpart" + hint);
                }
            }
        }
        if (runtime != null && (runtime.holdsLock() || runtime.waitingOnLock())) {
            var sameLock = into != null && "LOCK".equals(into.type) && from != null
                    && Objects.equals(from.lockName, into.lockName) && Objects.equals(from.lockKey, into.lockKey);
            if (!sameLock) {
                problems.add(error(id, (runtime.holdsLock() ? "the process holds the lock taken by '" + id + "'"
                        : "step '" + id + "' is queued for a lock") + ", and '" + to
                        + "' is not a LOCK of the same lockName and lockKey"));
            }
        }
        if (state != State.ACTIVE) {
            return new StepPlan(id, to, state, Action.REMAP, null);
        }
        if (from != null && into != null && (from.forEachItems != null || into.forEachItems != null)
                && !Objects.equals(from.forEachItems, into.forEachItems)) {
            problems.add(error(id, "forEach step '" + id + "' is running its iterations, and '" + to
                    + "' is not a forEach over the same items"));
        }
        if (runtime != null && runtime.escalationsFired() > 0 && into != null
                && runtime.escalationsFired() > into.escalationLevels) {
            problems.add(error(id, "human task '" + id + "' has already fired " + runtime.escalationsFired()
                    + " escalation level(s), and '" + to + "' declares only " + into.escalationLevels
                    + ": a target keeps every level that fired (levels are matched by position)"));
        }
        if (runtime != null && runtime.atWorker()) {
            if (from == null || into == null || sameContract(from.raw, into.raw)) {
                return new StepPlan(id, to, state, Action.KEEP_TASK, "the task in flight is kept");
            }
            problems.add(warning(id, "the task of '" + id + "' in flight is cancelled and dispatched again as '" + to
                    + "': its contract changed"));
            return new StepPlan(id, to, state, Action.REDISPATCH, "contract changed: cancelled and dispatched again");
        }
        return new StepPlan(id, to, state, Action.REARM, "deadline and subscription recomputed");
    }

    private static void checkPreconditions(List<StepPlan> steps, Map<String, Node> target, Set<String> done,
                                           List<Problem> problems) {
        for (var step : steps) {
            var running = step.action() == Action.KEEP_TASK || step.action() == Action.REDISPATCH
                    || step.action() == Action.REARM || (step.action() == Action.REMAP && step.state() == State.FAILED);
            if (!running) {
                continue;
            }
            var node = target.get(step.targetStepId());
            if (node == null || node.preconditions.isEmpty()) {
                continue;
            }
            var missing = node.preconditions.stream().filter(p -> !done.contains(p)).toList();
            var satisfied = node.anyOf ? missing.size() < node.preconditions.size() : missing.isEmpty();
            if (!satisfied) {
                problems.add(error(step.targetStepId(), "'" + step.targetStepId() + "' would be " + step.state()
                        + " before its precondition(s) " + missing + " completed in the target version"));
            }
        }
    }

    private static void checkVariables(List<StepPlan> steps, Map<String, Node> target, Set<String> done,
                                       ProcessFacts facts, Map<String, String> provided, List<Problem> problems) {
        var present = new TreeSet<>(facts.variables());
        present.addAll(provided.keySet());
        for (var step : steps) {
            var node = step.targetStepId() == null ? null : target.get(step.targetStepId());
            if (node == null) {
                continue;
            }
            if (step.action() == Action.REARM) {
                var missing = new TreeSet<>(node.readsNow);
                missing.removeAll(present);
                if (!missing.isEmpty()) {
                    problems.add(error(node.id, "'" + node.id + "' waits on variable(s) " + missing
                            + " the process does not have; give them in the variable mapping"));
                }
                continue;
            }
            var toRun = step.action() == Action.ADD
                    || (step.action() == Action.REMAP && step.state() == State.NOT_STARTED)
                    || step.action() == Action.REDISPATCH;
            if (!toRun || !done.containsAll(node.preconditions)) {
                continue;
            }
            var missing = new TreeSet<>(node.readsNow);
            missing.addAll(node.readsLater);
            missing.removeAll(present);
            if (!missing.isEmpty()) {
                problems.add(warning(node.id, "'" + node.id + "' is ready to run and reads variable(s) " + missing
                        + " the process does not have"));
            }
        }
    }

    private static void checkProcessLock(Map<?, ?> source, Map<?, ?> target, ProcessFacts facts, List<Problem> problems) {
        var from = lockOf(source);
        var to = lockOf(target);
        if (Objects.equals(from, to)) {
            return;
        }
        if (from != null && facts.holdsProcessLock()) {
            if (to == null) {
                problems.add(warning(null, "the target version declares no processLock: the one the process holds ("
                        + from + ") stays held until it ends"));
            } else {
                problems.add(error(null, "the process holds processLock " + from + " and the target version's is "
                        + to + ": finish or pause it under the old lock first"));
            }
        } else if (to != null) {
            problems.add(warning(null, "the target version declares processLock " + to
                    + ": the process takes it at its next step"));
        }
    }

    private static String lockOf(Map<?, ?> lock) {
        if (lock == null) {
            return null;
        }
        var name = text(lock, "name");
        return (name == null ? "<definition>" : name) + "/" + text(lock, "key");
    }

    // ── Definitions only (CI) ────────────────────────────────────────────────────────────────────

    /**
     * Whether every process of the {@code source} version could be moved onto the {@code target} one:
     * each source step a process can be waiting in needs a compatible counterpart (an error), an
     * instantaneous one without a counterpart is a warning, and so are the things that would refuse or
     * re-dispatch a process in some states — a changed task contract, a forEach over other items, a LOCK
     * of another lock.
     */
    public static List<Problem> checkDefinitions(List<? extends Map<?, ?>> sourceSteps,
                                                 List<? extends Map<?, ?>> targetSteps,
                                                 Map<String, String> stepMapping) {
        var problems = new ArrayList<Problem>();
        var source = nodes(sourceSteps);
        var target = nodes(targetSteps);
        var mapping = mapping(source.keySet(), source, target, stepMapping == null ? Map.of() : stepMapping,
                List.of(), problems);
        for (var node : source.values()) {
            var to = mapping.get(node.id);
            if (to == null) {
                var hint = target.containsKey(node.id)
                        ? " (the target's '" + node.id + "' is a " + target.get(node.id).type + ")" : "";
                if (waitable(node.raw)) {
                    problems.add(error(node.id, "step '" + node.id + "' (" + node.type + ") has no compatible counterpart"
                            + hint + ": a process waiting in it could not be migrated"));
                } else {
                    problems.add(warning(node.id, "step '" + node.id + "' (" + node.type + ") has no counterpart" + hint
                            + "; a process past it keeps it as history"));
                }
                continue;
            }
            var into = target.get(to);
            if (DISPATCHING_TYPES.contains(node.type) && !sameContract(node.raw, into.raw)) {
                problems.add(warning(node.id, "'" + node.id + "' → '" + to + "': the task contract changed; a task in"
                        + " flight is cancelled and dispatched again"));
            }
            if ((node.forEachItems != null || into.forEachItems != null)
                    && !Objects.equals(node.forEachItems, into.forEachItems)) {
                problems.add(warning(node.id, "'" + node.id + "' → '" + to + "': not a forEach over the same items; a"
                        + " process running its iterations could not be migrated"));
            }
            if (into.escalationLevels < node.escalationLevels) {
                problems.add(warning(node.id, "'" + node.id + "' → '" + to + "': " + node.escalationLevels
                        + " escalation level(s) become " + into.escalationLevels + "; a task that already escalated past"
                        + " level " + into.escalationLevels + " could not be migrated"));
            }
            if ("LOCK".equals(node.type) && (!Objects.equals(node.lockName, into.lockName)
                    || !Objects.equals(node.lockKey, into.lockKey))) {
                problems.add(warning(node.id, "'" + node.id + "' → '" + to + "': another lock; a process holding or"
                        + " queued for it could not be migrated"));
            }
        }
        return problems;
    }

    /**
     * What a change of sensitive variables between two versions means for a migration — warnings, never a
     * refusal: the engine re-encodes the values it stores as the target declares (a variable that becomes
     * sensitive is encrypted, one that stops being so is stored in clear from then on).
     *
     * @param variables the variables concerned — a process's (and the migration's mapping), or null to
     *                  compare the declarations themselves (the definitions-only check)
     */
    public static List<Problem> sensitivityProblems(Collection<String> sourcePatterns, Collection<String> targetPatterns,
                                                    Collection<String> variables) {
        var problems = new ArrayList<Problem>();
        var source = SensitiveVariableRules.matcher(sourcePatterns == null ? List.of() : List.copyOf(sourcePatterns));
        var target = SensitiveVariableRules.matcher(targetPatterns == null ? List.of() : List.copyOf(targetPatterns));
        if (variables == null) {
            var before = new java.util.LinkedHashSet<>(sourcePatterns == null ? List.<String>of() : sourcePatterns);
            var after = new java.util.LinkedHashSet<>(targetPatterns == null ? List.<String>of() : targetPatterns);
            var added = after.stream().filter(p -> !before.contains(p)).toList();
            var removed = before.stream().filter(p -> !after.contains(p)).toList();
            if (!added.isEmpty()) {
                problems.add(warning(null, "sensitive variables " + added + " are added: a migrated process has their"
                        + " stored values encrypted (the engine needs workflow.encryption.keys)"));
            }
            if (!removed.isEmpty()) {
                problems.add(warning(null, "sensitive variables " + removed + " are removed: a migrated process stores"
                        + " their values in clear from then on"));
            }
            return problems;
        }
        var becoming = new java.util.TreeSet<String>();
        var ceasing = new java.util.TreeSet<String>();
        for (var name : variables) {
            if (name == null) continue;
            boolean was = source.test(name), is = target.test(name);
            if (is && !was) becoming.add(name);
            if (was && !is) ceasing.add(name);
        }
        if (!becoming.isEmpty()) {
            problems.add(warning(null, "variables " + becoming + " become sensitive: their stored values are"
                    + " encrypted by the migration"));
        }
        if (!ceasing.isEmpty()) {
            problems.add(warning(null, "variables " + ceasing + " stop being sensitive: their values are stored in"
                    + " clear from the migration on"));
        }
        return problems;
    }

    /** The default mapping between two versions: same id, compatible type. */
    public static Map<String, String> defaultMapping(List<? extends Map<?, ?>> sourceSteps,
                                                     List<? extends Map<?, ?>> targetSteps) {
        var source = nodes(sourceSteps);
        return mapping(source.keySet(), source, nodes(targetSteps), Map.of(), List.of(), new ArrayList<>());
    }

    // ── Expressions ──────────────────────────────────────────────────────────────────────────────

    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Set<String> KEYWORDS = Set.of("true", "false", "null", "and", "or", "not", "eq", "ne", "lt",
            "gt", "le", "ge", "empty", "size", "new", "var", "let", "const", "if", "else", "for", "while", "do",
            "return", "function", "in", "div", "mod", "matches", "instanceof", "NaN", "Infinity");

    /**
     * The variables a JEXL expression reads: the top-level names in it — not a property after a dot, not
     * a function or a namespace, not a keyword, not inside a string literal. A heuristic, which is all a
     * warning needs: it errs on the side of naming a variable that is not one.
     */
    public static Set<String> identifiers(String expression) {
        var names = new TreeSet<String>();
        if (expression == null || expression.isBlank()) {
            return names;
        }
        var text = STRING_LITERAL.matcher(expression).replaceAll("''");
        var matcher = IDENTIFIER.matcher(text);
        while (matcher.find()) {
            var name = matcher.group();
            int before = matcher.start() - 1;
            while (before >= 0 && text.charAt(before) == ' ') {
                before--;
            }
            if (before >= 0 && (text.charAt(before) == '.' || Character.isDigit(text.charAt(before)))) {
                continue;
            }
            int after = matcher.end();
            while (after < text.length() && text.charAt(after) == ' ') {
                after++;
            }
            if (after < text.length() && (text.charAt(after) == '(' || text.charAt(after) == ':')) {
                continue;
            }
            if (!KEYWORDS.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    // ── The generic form ─────────────────────────────────────────────────────────────────────────

    /** One step as far as these rules care, read off its generic form. */
    private static final class Node {
        final Map<?, ?> raw;
        final String id;
        final String type;
        final List<String> preconditions;
        final boolean anyOf;
        final String compensationStepId;
        final boolean compensable;
        final String onTimeoutStepId;
        final String forEachItems;
        final String lockName;
        final String lockKey;
        /** For a USER_TASK, how many escalation levels it declares. */
        final int escalationLevels;
        /** Read while the step waits: re-arming it evaluates them now. */
        final Set<String> readsNow = new TreeSet<>();
        /** Read when it starts: its guards, its items. */
        final Set<String> readsLater = new TreeSet<>();

        Node(Map<?, ?> raw) {
            this.raw = raw;
            this.id = text(raw, "id");
            this.type = normalisedType(text(raw, "type"));
            var links = new ArrayList<String>();
            var guards = new ArrayList<String>();
            if (raw.get("preconditions") instanceof List<?> list && !list.isEmpty()) {
                for (var item : list) {
                    if (item instanceof Map<?, ?> link && text(link, "stepId") != null) {
                        links.add(text(link, "stepId"));
                        guards.add(text(link, "expression"));
                    }
                }
            } else if (raw.get("preconditionStepIds") instanceof List<?> list && !list.isEmpty()) {
                list.stream().filter(Objects::nonNull).map(String::valueOf).forEach(links::add);
            } else if (text(raw, "preconditionStepId") != null) {
                links.add(text(raw, "preconditionStepId"));
            }
            guards.add(text(raw, "preconditionExpression"));
            this.preconditions = List.copyOf(links);
            this.anyOf = "XOR".equals(text(raw, "joinType"));
            this.compensationStepId = text(raw, "compensationStepId");
            this.compensable = Boolean.TRUE.equals(raw.get("compensable")) || Boolean.TRUE.equals(raw.get("rollbackable"));
            this.onTimeoutStepId = text(raw, "onTimeoutStepId");
            this.forEachItems = raw.get("forEach") instanceof Map<?, ?> forEach ? text(forEach, "items") : null;
            this.lockName = text(raw, "lockName");
            this.lockKey = text(raw, "lockKey");
            this.escalationLevels = raw.get("escalation") instanceof List<?> levels ? levels.size() : 0;

            var until = text(raw, "untilVariable");
            if (until != null) {
                readsNow.add(until);
            }
            readsNow.addAll(identifiers(text(raw, "correlationExpression")));
            if ("LOCK".equals(type)) {
                readsNow.addAll(identifiers(lockKey));
            }
            for (var moment : List.of("until", "deadline")) {
                if (raw.get(moment) instanceof Map<?, ?> m) {
                    readsNow.addAll(identifiers(text(m, "date")));
                } else if (raw.get(moment) instanceof String date) {
                    readsNow.addAll(identifiers(date));
                }
            }
            if (raw.get("awaitReply") instanceof Map<?, ?> await) {
                readsNow.addAll(identifiers(text(await, "correlationExpression")));
                if (await.get("onEvent") instanceof Map<?, ?> onEvent) {
                    readsNow.addAll(identifiers(text(onEvent, "correlationKey")));
                }
            }
            if (raw.get("onEvent") instanceof Map<?, ?> onEvent) {
                readsNow.addAll(identifiers(text(onEvent, "correlationKey")));
            }
            guards.forEach(guard -> readsLater.addAll(identifiers(guard)));
            readsLater.addAll(identifiers(forEachItems));
        }
    }

    private static Map<String, Node> nodes(List<? extends Map<?, ?>> steps) {
        var nodes = new LinkedHashMap<String, Node>();
        if (steps == null) {
            return nodes;
        }
        for (var step : steps) {
            if (step == null) {
                continue;
            }
            var node = new Node(step);
            if (node.id != null) {
                nodes.putIfAbsent(node.id, node);
            }
        }
        return nodes;
    }

    private static Map<String, Set<String>> successors(Collection<Node> nodes) {
        var successors = new HashMap<String, Set<String>>();
        for (var node : nodes) {
            node.preconditions.forEach(p -> successors.computeIfAbsent(p, k -> new LinkedHashSet<>()).add(node.id));
            if (node.onTimeoutStepId != null) {
                successors.computeIfAbsent(node.id, k -> new LinkedHashSet<>()).add(node.onTimeoutStepId);
            }
        }
        return successors;
    }

    private static Set<String> reach(String from, Map<String, Set<String>> successors) {
        var seen = new LinkedHashSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add(from);
        while (!queue.isEmpty()) {
            var id = queue.poll();
            if (seen.add(id)) {
                queue.addAll(successors.getOrDefault(id, Set.of()));
            }
        }
        return seen;
    }

    private static State stateOf(Map<String, StepState> states, String id) {
        var state = states.get(id);
        return state == null ? State.NOT_STARTED : state.state();
    }

    /** The type's name, with the pre-rename alias {@code MESSAGE} read as {@code WAIT_FOR_MESSAGE}. */
    static String normalisedType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        var upper = type.strip();
        return "MESSAGE".equals(upper) ? "WAIT_FOR_MESSAGE" : upper;
    }

    private static String text(Map<?, ?> map, String key) {
        var value = map == null ? null : map.get(key);
        if (value == null) {
            return null;
        }
        var text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    /** Null, a blank string, an empty map or list: all "not set", so a default spelled out compares equal. */
    private static Object blankToNull(Object value) {
        if (value instanceof String s && s.isBlank()) {
            return null;
        }
        if (value instanceof Map<?, ?> m && m.isEmpty()) {
            return null;
        }
        if (value instanceof Collection<?> c && c.isEmpty()) {
            return null;
        }
        return value;
    }

    private static Problem error(String stepId, String message) {
        return new Problem(Severity.ERROR, stepId, message);
    }

    private static Problem warning(String stepId, String message) {
        return new Problem(Severity.WARNING, stepId, message);
    }
}
