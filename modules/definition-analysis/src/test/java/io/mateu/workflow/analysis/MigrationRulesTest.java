package io.mateu.workflow.analysis;

import io.mateu.workflow.analysis.MigrationRules.Action;
import io.mateu.workflow.analysis.MigrationRules.Input;
import io.mateu.workflow.analysis.MigrationRules.ProcessFacts;
import io.mateu.workflow.analysis.MigrationRules.Problem;
import io.mateu.workflow.analysis.MigrationRules.Severity;
import io.mateu.workflow.analysis.MigrationRules.State;
import io.mateu.workflow.analysis.MigrationRules.StepState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationRulesTest {

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> map(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> step(String id, String type, String precondition, Object... more) {
        var step = map("id", id, "type", type);
        if (precondition != null) {
            step.put("preconditionStepId", precondition);
        }
        step.putAll(map(more));
        return step;
    }

    /** start → a → b → c → end, all ACTION on topic t-<id>. */
    private static List<Map<String, Object>> v1() {
        return new ArrayList<>(List.of(step("start", "START", null), step("a", "ACTION", "start", "topic", "t-a"),
                step("b", "ACTION", "a", "topic", "t-b"), step("c", "ACTION", "b", "topic", "t-c"),
                step("end", "END", "c")));
    }

    private static ProcessFacts running(String... variables) {
        return new ProcessFacts("RUNNING", false, false, Set.of(variables));
    }

    private static List<StepState> states(Object... idAndState) {
        var list = new ArrayList<StepState>();
        for (int i = 0; i < idAndState.length; i += 2) {
            var value = idAndState[i + 1];
            list.add(value instanceof StepState s ? s : new StepState((String) idAndState[i], (State) value));
        }
        return list;
    }

    private static MigrationRules.Plan plan(List<Map<String, Object>> source, List<Map<String, Object>> target,
                                            Map<String, String> mapping, List<StepState> states, ProcessFacts facts) {
        return MigrationRules.plan(new Input(source, null, target, null, mapping, Map.of(), states, facts));
    }

    private static List<String> messages(List<Problem> problems) {
        return problems.stream().map(Problem::message).toList();
    }

    // ── plan: the actions ─────────────────────────────────────────────────────────────────────────

    @Test
    void anIdenticalVersionRemapsEverythingAndKeepsTheTaskInFlight() {
        var plan = plan(v1(), v1(), Map.of(), states("start", State.COMPLETED, "a", State.COMPLETED,
                "b", new StepState("b", State.ACTIVE, true, false, false, false)), running());

        assertThat(plan.executable()).isTrue();
        assertThat(plan.problems()).isEmpty();
        assertThat(plan.mapping()).containsEntry("a", "a").containsEntry("end", "end");
        assertThat(plan.ofSource("a").action()).isEqualTo(Action.REMAP);
        assertThat(plan.ofSource("b").action()).isEqualTo(Action.KEEP_TASK);
        assertThat(plan.ofSource("c").action()).isEqualTo(Action.REMAP);
        assertThat(plan.ofSource("nope")).isNull();
    }

    @Test
    void aRenamedStepIsMappedExplicitlyAndAChangedContractRedispatches() {
        var target = v1();
        target.set(2, step("b2", "HTTP_CALL", "a", "http", map("url", "https://x")));
        target.set(3, step("c", "ACTION", "b2", "topic", "t-c"));
        var plan = plan(v1(), target, Map.of("b", "b2"), states("start", State.COMPLETED, "a", State.COMPLETED,
                "b", new StepState("b", State.ACTIVE, true, false, false, false)), running());

        assertThat(plan.executable()).isTrue();
        assertThat(plan.ofSource("b").targetStepId()).isEqualTo("b2");
        assertThat(plan.ofSource("b").action()).isEqualTo(Action.REDISPATCH);
        assertThat(messages(plan.warnings())).anyMatch(m -> m.contains("contract changed"));
    }

    @Test
    void waitingStepsAreRearmed() {
        var source = List.of(step("start", "START", null), step("wait", "TIMER", "start", "duration", 1000),
                step("end", "END", "wait"));
        var target = List.of(step("start", "START", null), step("wait", "TIMER", "start", "duration", 5000),
                step("end", "END", "wait"));
        var plan = plan(new ArrayList<>(source), new ArrayList<>(target), Map.of(),
                states("start", State.COMPLETED, "wait", State.ACTIVE), running());
        assertThat(plan.ofSource("wait").action()).isEqualTo(Action.REARM);
        assertThat(plan.executable()).isTrue();
    }

    @Test
    void addedAndRemovedSteps() {
        // v2 drops c, adds d after b, and adds x before a (upstream of where the process is).
        var target = new ArrayList<>(List.of(step("start", "START", null), step("x", "ACTION", "start"),
                step("a", "ACTION", "x", "topic", "t-a"), step("b", "ACTION", "a", "topic", "t-b"),
                step("d", "ACTION", "b"), step("end", "END", "d")));
        var plan = plan(v1(), target, Map.of(), states("start", State.COMPLETED, "a", State.COMPLETED,
                "b", new StepState("b", State.ACTIVE, true, false, false, false)), running());

        assertThat(plan.executable()).as(plan.problems().toString()).isTrue();
        assertThat(plan.ofSource("c").action()).isEqualTo(Action.DROP);
        assertThat(plan.steps()).anySatisfy(s -> {
            assertThat(s.targetStepId()).isEqualTo("d");
            assertThat(s.action()).isEqualTo(Action.ADD);
        });
        assertThat(plan.steps()).anySatisfy(s -> {
            assertThat(s.targetStepId()).isEqualTo("x");
            assertThat(s.action()).isEqualTo(Action.ADD_PASSED);
        });
        assertThat(messages(plan.warnings())).anyMatch(m -> m.contains("recorded as passed"));
    }

    @Test
    void aCompletedStepWithoutCounterpartIsHistoryAndACompensableOneIsWarnedAbout() {
        var source = v1();
        source.set(1, step("a", "ACTION", "start", "topic", "t-a", "compensable", true, "compensationStepId", "undo-a"));
        source.add(step("undo-a", "ACTION", null));
        var target = new ArrayList<>(List.of(step("start", "START", null), step("b", "ACTION", "start", "topic", "t-b"),
                step("c", "ACTION", "b", "topic", "t-c"), step("end", "END", "c")));
        var plan = plan(source, target, Map.of(), states("start", State.COMPLETED, "a", State.COMPLETED,
                "b", State.NOT_STARTED), running());

        assertThat(plan.ofSource("a").action()).isEqualTo(Action.HISTORY);
        assertThat(plan.ofSource("undo-a").action()).isEqualTo(Action.DROP);
        assertThat(messages(plan.warnings())).anyMatch(m -> m.contains("will not compensate"));
        assertThat(plan.executable()).isTrue();
    }

    @Test
    void injectedStepsAreCarriedAndCannotBeMapped() {
        var plan = plan(v1(), v1(), Map.of("dyn-1", "a"), states("start", State.COMPLETED,
                "dyn-1", new StepState("dyn-1", State.ACTIVE, true, false, false, true)), running());
        assertThat(plan.steps()).anySatisfy(s -> {
            assertThat(s.sourceStepId()).isEqualTo("dyn-1");
            assertThat(s.action()).isEqualTo(Action.CARRY);
        });
        assertThat(messages(plan.errors())).anyMatch(m -> m.contains("injected at runtime"));
    }

    @Test
    void aStepTheSnapshotDoesNotNameButTheProcessHasIsPlannedToo() {
        var plan = plan(v1(), v1(), Map.of(), states("ghost", State.COMPLETED), running());
        assertThat(plan.ofSource("ghost").action()).isEqualTo(Action.HISTORY);
    }

    // ── plan: refusals ────────────────────────────────────────────────────────────────────────────

    @Test
    void onlyALiveProcessWithoutARollbackIsMigrated() {
        var finished = plan(v1(), v1(), Map.of(), List.of(), new ProcessFacts("COMPLETED", false, false, null));
        assertThat(messages(finished.errors())).anyMatch(m -> m.contains("only a RUNNING, PAUSED or ERROR"));
        var none = plan(v1(), v1(), Map.of(), List.of(), null);
        assertThat(none.executable()).isFalse();
        var rollingBack = plan(v1(), v1(), Map.of(), List.of(), new ProcessFacts("ERROR", true, false, Set.of()));
        assertThat(messages(rollingBack.errors())).containsExactly("its saga rollback is under way");
    }

    @Test
    void aCompensationThatRanRefuses() {
        var source = v1();
        source.set(1, step("a", "ACTION", "start", "compensable", true, "compensationStepId", "undo-a"));
        source.add(step("undo-a", "ACTION", null));
        var plan = plan(source, source, Map.of(), states("a", State.COMPLETED, "undo-a", State.COMPLETED),
                new ProcessFacts("ERROR", false, false, Set.of()));
        assertThat(messages(plan.errors())).anyMatch(m -> m.contains("compensation step 'undo-a' has already run"));
    }

    @Test
    void anActiveOrFailedStepMustMap() {
        var target = new ArrayList<>(List.of(step("start", "START", null), step("a", "ACTION", "start"),
                step("b", "TIMER", "a"), step("end", "END", "b")));
        var plan = plan(v1(), target, Map.of(), states("start", State.COMPLETED, "a", State.COMPLETED,
                "b", State.ACTIVE, "c", State.FAILED), new ProcessFacts("ERROR", false, false, Set.of()));
        assertThat(messages(plan.errors()))
                .anyMatch(m -> m.contains("'b' is ACTIVE and has no counterpart") && m.contains("is a TIMER"))
                .anyMatch(m -> m.contains("'c' is FAILED and has no counterpart"));
        assertThat(plan.ofSource("b").action()).isNull();
    }

    @Test
    void theExplicitMappingIsChecked() {
        var target = v1();
        target.add(step("t", "TIMER", "a"));
        var plan = plan(v1(), target, map2("zzz", "a", "a", "missing", "b", "t", "c", ""),
                states("start", State.COMPLETED), running());
        assertThat(messages(plan.errors()))
                .anyMatch(m -> m.contains("names source step 'zzz'"))
                .anyMatch(m -> m.contains("'missing', which the target version does not have"))
                .anyMatch(m -> m.contains("cannot become 't' (TIMER)"));
        // c explicitly has no counterpart: dropped
        assertThat(plan.ofSource("c").action()).isEqualTo(Action.DROP);
        assertThat(plan.mapping()).doesNotContainKey("c");
    }

    @Test
    void twoStepsOntoOneTargetRefuse() {
        var plan = plan(v1(), v1(), Map.of("b", "a"), states("start", State.COMPLETED), running());
        assertThat(messages(plan.errors())).anyMatch(m -> m.contains("are all mapped onto 'a'"));
    }

    @Test
    void anActiveStepBeforeItsTargetPreconditionsRefuses() {
        // v2 swaps b and c: b is running, and in v2 it waits for c, which has not run. An XOR link with one
        // route done is satisfied.
        var target = new ArrayList<>(List.of(step("start", "START", null), step("a", "ACTION", "start", "topic", "t-a"),
                step("c", "ACTION", "a", "topic", "t-c"),
                step("b", "ACTION", null, "topic", "t-b", "preconditionStepIds", List.of("c")),
                step("w", "TIMER", null, "joinType", "XOR", "preconditions",
                        List.of(map("stepId", "a"), map("stepId", "c", "expression", "ok"))),
                step("end", "END", "b")));
        var source = v1();
        source.add(step("w", "TIMER", "a"));
        var plan = plan(source, target, Map.of(), states("start", State.COMPLETED, "a", State.COMPLETED,
                "b", new StepState("b", State.ACTIVE, true, false, false, false), "w", State.ACTIVE), running());
        assertThat(messages(plan.errors()))
                .anyMatch(m -> m.contains("'b' would be ACTIVE before its precondition(s) [c]"))
                .noneMatch(m -> m.contains("'w' would be"));
    }

    @Test
    void anActiveForEachNeedsAForEachOverTheSameItems() {
        var source = List.of(step("start", "START", null),
                step("each", "ACTION", "start", "forEach", map("items", "lines")), step("end", "END", "each"));
        var same = List.of(step("start", "START", null),
                step("each", "ACTION", "start", "forEach", map("items", "lines")), step("end", "END", "each"));
        var other = List.of(step("start", "START", null),
                step("each", "ACTION", "start", "forEach", map("items", "order.lines")), step("end", "END", "each"));
        var active = states("start", State.COMPLETED, "each", State.ACTIVE);

        var ok = plan(new ArrayList<>(source), new ArrayList<>(same), Map.of(), active, running("lines"));
        assertThat(ok.executable()).isTrue();
        assertThat(ok.ofSource("each").action()).isEqualTo(Action.REARM);
        var refused = plan(new ArrayList<>(source), new ArrayList<>(other), Map.of(), active, running());
        assertThat(messages(refused.errors())).anyMatch(m -> m.contains("not a forEach over the same items"));
    }

    @Test
    void locksMustBeKept() {
        var source = List.of(step("start", "START", null),
                step("lock", "LOCK", "start", "lockName", "room", "lockKey", "roomId"),
                step("work", "ACTION", "lock"), step("unlock", "UNLOCK", "work", "lockName", "room", "lockKey", "roomId"),
                step("end", "END", "unlock"));
        var otherLock = List.of(step("start", "START", null),
                step("lock", "LOCK", "start", "lockName", "suite", "lockKey", "roomId"),
                step("work", "ACTION", "lock"), step("unlock", "UNLOCK", "work", "lockName", "suite", "lockKey", "roomId"),
                step("end", "END", "unlock"));
        var holding = states("start", State.COMPLETED,
                "lock", new StepState("lock", State.COMPLETED, false, false, true, false),
                "work", new StepState("work", State.ACTIVE, true, false, false, false));
        assertThat(plan(new ArrayList<>(source), new ArrayList<>(source), Map.of(), holding, running("roomId")).executable())
                .isTrue();
        assertThat(messages(plan(new ArrayList<>(source), new ArrayList<>(otherLock), Map.of(), holding, running()).errors()))
                .anyMatch(m -> m.contains("holds the lock taken by 'lock'"));

        var queued = states("start", State.COMPLETED,
                "lock", new StepState("lock", State.ACTIVE, false, true, false, false));
        var plan = plan(new ArrayList<>(source), new ArrayList<>(otherLock), Map.of(), queued, running("roomId"));
        assertThat(messages(plan.errors())).anyMatch(m -> m.contains("is queued for a lock"));
    }

    @Test
    void theProcessLock() {
        var room = map("name", "room", "key", "roomId");
        var suite = map("key", "suiteId");
        var holds = new ProcessFacts("RUNNING", false, true, Set.of());
        assertThat(messages(MigrationRules.plan(new Input(v1(), room, v1(), suite, null, null, null, holds)).errors()))
                .anyMatch(m -> m.contains("holds processLock room/roomId") && m.contains("<definition>/suiteId"));
        assertThat(messages(MigrationRules.plan(new Input(v1(), room, v1(), null, null, null, null, holds)).warnings()))
                .anyMatch(m -> m.contains("stays held until it ends"));
        assertThat(messages(MigrationRules.plan(new Input(v1(), null, v1(), room, null, null, null, holds)).warnings()))
                .anyMatch(m -> m.contains("takes it at its next step"));
        assertThat(MigrationRules.plan(new Input(v1(), room, v1(), room, null, null, null, holds)).problems()).isEmpty();
        assertThat(MigrationRules.plan(new Input(v1(), room, v1(), suite, null, null, null, running())).warnings())
                .hasSize(1);
    }

    @Test
    void variablesARearmedStepWaitsOnMustBePresentOrGiven() {
        var source = List.of(step("start", "START", null), step("wait", "WAIT_FOR_MESSAGE", "start",
                "messageName", "paid", "correlationExpression", "orderId"), step("end", "END", "wait"));
        var target = List.of(step("start", "START", null), step("wait", "WAIT_FOR_MESSAGE", "start",
                "messageName", "paid", "correlationExpression", "invoice.id + '-' + region"), step("end", "END", "wait"));
        var active = states("start", State.COMPLETED, "wait", State.ACTIVE);
        var missing = plan(new ArrayList<>(source), new ArrayList<>(target), Map.of(), active, running("orderId"));
        assertThat(messages(missing.errors())).anyMatch(m -> m.contains("waits on variable(s) [invoice, region]"));

        var given = MigrationRules.plan(new Input(new ArrayList<>(source), null, new ArrayList<>(target), null,
                Map.of(), Map.of("invoice", "{}", "region", "EU"), active, running()));
        assertThat(given.executable()).isTrue();
    }

    @Test
    void aStepReadyToRunThatReadsAMissingVariableIsAWarning() {
        var target = v1();
        target.set(3, step("c", "ACTION", null, "preconditions", List.of(map("stepId", "b", "expression", "amount > 100"))));
        var plan = plan(v1(), target, Map.of(), states("start", State.COMPLETED, "a", State.COMPLETED,
                "b", State.COMPLETED), running());
        assertThat(messages(plan.warnings())).anyMatch(m -> m.contains("reads variable(s) [amount]"));
        assertThat(plan.executable()).isTrue();
        // not ready yet (b still to run): nothing said
        var notReady = plan(v1(), target, Map.of(), states("start", State.COMPLETED, "a", State.COMPLETED), running());
        assertThat(notReady.warnings()).isEmpty();
    }

    @Test
    void readsOfTimersLocksAndEventWaits() {
        var source = List.of(step("start", "START", null),
                step("t", "TIMER", "start", "untilVariable", "due"),
                step("m", "TIMER", "start", "until", map("date", "checkIn")),
                step("d", "ACTION", "start", "deadline", "slaDate"),
                step("e", "ACTION", "start", "onEvent", map("correlationKey", "orderId")),
                step("h", "HTTP_CALL", "start", "awaitReply", map("correlationExpression", "ref",
                        "onEvent", map("correlationKey", "ref2"))),
                step("l", "LOCK", "start", "lockName", "x", "lockKey", "lockId"));
        var plan = plan(new ArrayList<>(source), new ArrayList<>(source), Map.of(),
                states("start", State.COMPLETED, "t", State.ACTIVE, "m", State.ACTIVE, "d", State.ACTIVE,
                        "e", State.ACTIVE, "h", State.ACTIVE,
                        "l", new StepState("l", State.ACTIVE, false, true, false, false)), running());
        assertThat(messages(plan.errors()))
                .anyMatch(m -> m.contains("[due]")).anyMatch(m -> m.contains("[checkIn]"))
                .anyMatch(m -> m.contains("[slaDate]")).anyMatch(m -> m.contains("[orderId]"))
                .anyMatch(m -> m.contains("[ref, ref2]")).anyMatch(m -> m.contains("[lockId]"));
    }

    // ── definitions (CI) ─────────────────────────────────────────────────────────────────────────

    @Test
    void checkDefinitionsReportsWaitableStepsWithoutCounterpart() {
        var target = new ArrayList<>(List.of(step("start", "START", null), step("a", "HTTP_CALL", "start", "http", map("url", "u")),
                step("b", "TIMER", "a"), step("end", "END", "b")));
        var source = v1();
        source.add(step("fork", "FORK", "start"));
        source.add(step("pub", "PUBLISH_EVENT", "start"));
        source.add(step("ask", "PUBLISH_EVENT", "start", "awaitReply", map("messageName", "r")));
        var problems = MigrationRules.checkDefinitions(source, target, null);

        assertThat(problems).anySatisfy(p -> {
            assertThat(p.severity()).isEqualTo(Severity.ERROR);
            assertThat(p.stepId()).isEqualTo("b");
            assertThat(p.message()).contains("no compatible counterpart (the target's 'b' is a TIMER)");
        });
        assertThat(problems).anySatisfy(p -> assertThat(p.toString()).startsWith("ERROR [c]"));
        assertThat(problems).anySatisfy(p -> assertThat(p.toString()).startsWith("ERROR [ask]"));
        assertThat(problems).anySatisfy(p -> assertThat(p.toString()).startsWith("WARNING [fork]"));
        assertThat(problems).anySatisfy(p -> assertThat(p.toString()).startsWith("WARNING [pub]"));
        assertThat(problems).anySatisfy(p -> assertThat(p.message()).contains("'a' → 'a': the task contract changed"));
    }

    @Test
    void checkDefinitionsWithAMappingAndShapeChanges() {
        var source = List.of(step("start", "START", null), step("each", "ACTION", "start", "forEach", map("items", "xs")),
                step("lock", "LOCK", "each", "lockName", "a", "lockKey", "k"), step("old", "USER_TASK", "lock", "formId", "f"));
        var target = List.of(step("start", "START", null), step("each", "ACTION", "start"),
                step("lock", "LOCK", "each", "lockName", "b", "lockKey", "k"), step("new", "USER_TASK", "lock", "formId", "f"));
        var problems = MigrationRules.checkDefinitions(new ArrayList<>(source), new ArrayList<>(target), Map.of("old", "new"));
        assertThat(problems).extracting(Problem::severity).containsOnly(Severity.WARNING);
        assertThat(messages(problems))
                .anyMatch(m -> m.contains("not a forEach over the same items"))
                .anyMatch(m -> m.contains("another lock"));
        assertThat(MigrationRules.checkDefinitions(new ArrayList<>(source), new ArrayList<>(source), null)).isEmpty();
    }

    @Test
    void theDefaultMapping() {
        var target = v1();
        target.set(2, step("b", "TIMER", "a"));
        assertThat(MigrationRules.defaultMapping(v1(), target)).containsKeys("start", "a", "c", "end").doesNotContainKey("b");
        assertThat(MigrationRules.defaultMapping(null, null)).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void compatibilityAndContracts() {
        assertThat(MigrationRules.compatible("ACTION", "HTTP_CALL")).isTrue();
        assertThat(MigrationRules.compatible("MESSAGE", "WAIT_FOR_MESSAGE")).isTrue();
        assertThat(MigrationRules.compatible("ACTION", "TIMER")).isFalse();
        assertThat(MigrationRules.compatible(null, "TIMER")).isFalse();
        assertThat(MigrationRules.compatible("ACTION", " ")).isFalse();
        assertThat(MigrationRules.sameContract(map("type", "ACTION", "topic", "x", "http", map(), "timeout", 5),
                map("type", "ACTION", "topic", "x", "llm", List.of(), "formId", ""))).isTrue();
        assertThat(MigrationRules.sameContract(map("type", "ACTION", "topic", "x"), map("type", "ACTION", "topic", "y")))
                .isFalse();
        assertThat(MigrationRules.waitable(map("type", "TIMER"))).isTrue();
        assertThat(MigrationRules.waitable(map())).isFalse();
    }

    @Test
    void identifiersOfAnExpression() {
        assertThat(MigrationRules.identifiers("order.total > 100 and customer.vip == true")).containsExactly("customer", "order");
        assertThat(MigrationRules.identifiers("size(items) > 0 && str:upper(name) == 'BOB x'")).containsExactly("items", "name");
        assertThat(MigrationRules.identifiers("amount . currency + 1e5 + x")).containsExactly("amount", "x");
        assertThat(MigrationRules.identifiers(null)).isEmpty();
        assertThat(MigrationRules.identifiers(" ")).isEmpty();
    }

    @Test
    void stepsWithoutIdsOrNullEntriesAreIgnoredAndOldFieldsRead() {
        var source = new ArrayList<Map<String, Object>>();
        source.add(null);
        source.add(map("type", "ACTION"));
        source.add(step("a", "ACTION", null, "preconditionStepIds", List.of("start"), "rollbackable", true,
                "compensationStepId", "u"));
        source.add(step("start", "START", null));
        var plan = plan(source, List.of(step("start", "START", null)), Map.of(), states("a", State.COMPLETED), running());
        assertThat(plan.ofSource("a").action()).isEqualTo(Action.HISTORY);
        assertThat(messages(plan.warnings())).anyMatch(m -> m.contains("will not compensate"));
        assertThat(new StepState("x", null).state()).isEqualTo(State.NOT_STARTED);
        assertThat(new Input(null, null, null, null, null, null, null, null).facts().variables()).isEmpty();
    }

    @Test
    void aTimeoutRouteIsDownstreamForAddedSteps() {
        var target = new ArrayList<>(List.of(step("start", "START", null), step("n", "ACTION", "start", "onTimeoutStepId", "a"),
                step("a", "ACTION", null, "topic", "t-a"), step("b", "ACTION", "a", "topic", "t-b"),
                step("c", "ACTION", "b", "topic", "t-c"), step("end", "END", "c")));
        var plan = plan(v1(), target, Map.of(), states("start", State.COMPLETED, "a", State.COMPLETED), running());
        assertThat(plan.steps()).anySatisfy(s -> {
            assertThat(s.targetStepId()).isEqualTo("n");
            assertThat(s.action()).isEqualTo(Action.ADD_PASSED);
        });
    }

    @Test
    void aHumanTaskKeepsEveryEscalationLevelThatFired() {
        var two = List.of(map("after", "PT1H", "action", "notify"), map("after", "PT4H", "action", "reassign"));
        var one = List.of(map("after", "PT2H", "action", "notify"));
        var source = List.of(step("start", "START", null), step("approve", "USER_TASK", "start", "formId", "f",
                "escalation", two), step("end", "END", "approve"));
        var shorter = List.of(step("start", "START", null), step("approve", "USER_TASK", "start", "formId", "f",
                "escalation", one), step("end", "END", "approve"));
        var firedOne = states("start", State.COMPLETED,
                "approve", new StepState("approve", State.ACTIVE, true, false, false, false, 1));
        var firedTwo = states("start", State.COMPLETED,
                "approve", new StepState("approve", State.ACTIVE, true, false, false, false, 2));

        var ok = plan(new ArrayList<>(source), new ArrayList<>(shorter), Map.of(), firedOne, running());
        assertThat(ok.executable()).isTrue();
        assertThat(ok.ofSource("approve").action()).isEqualTo(Action.KEEP_TASK);
        assertThat(messages(plan(new ArrayList<>(source), new ArrayList<>(shorter), Map.of(), firedTwo, running()).errors()))
                .anyMatch(m -> m.contains("already fired 2 escalation level(s)") && m.contains("declares only 1"));
        assertThat(messages(MigrationRules.checkDefinitions(new ArrayList<>(source), new ArrayList<>(shorter), null)))
                .anyMatch(m -> m.contains("2 escalation level(s) become 1"));
    }

    private static Map<String, String> map2(String... keyValues) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
