package io.mateu.workflow.analysis;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LoopRulesTest {

    private static LoopRules.Node n(String id, String type, String... preconditions) {
        return new LoopRules.Node(id, type, List.of(preconditions), null, null, null, null);
    }

    private static LoopRules.Node forEach(String id, String type, Map<String, Object> forEach, String... preconditions) {
        return new LoopRules.Node(id, type, List.of(preconditions), null, forEach, null, null);
    }

    private static LoopRules.Node repeat(String id, String type, Map<String, Object> repeat, String... preconditions) {
        return new LoopRules.Node(id, type, List.of(preconditions), null, null, repeat, null);
    }

    private static LoopRules.Node loopBack(String id, String type, Map<String, Object> loopBack, String... preconditions) {
        return new LoopRules.Node(id, type, List.of(preconditions), null, null, null, loopBack);
    }

    private static Map<String, Object> map(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static List<LoopRules.Node> flow(LoopRules.Node... extra) {
        var nodes = new ArrayList<>(List.of(n("start", "START"), n("a", "ACTION", "start"), n("b", "ACTION", "a"),
                n("c", "ACTION", "b"), n("end", "END", "c")));
        for (var node : extra) {
            nodes.removeIf(existing -> existing.id().equals(node.id()));
            nodes.add(node);
        }
        return nodes;
    }

    @Test
    void aWellFormedForEachRepeatAndLoopBackPass() {
        var report = LoopRules.analyze(flow(
                forEach("a", "ACTION", map("items", "rooms", "as", "room", "mode", "parallel", "maxConcurrency", 2,
                        "output", "res", "outputElement", "id", "completion", "any"), "start"),
                repeat("b", "HTTP_CALL", map("until", "done", "maxIterations", 5, "delay", "PT30S"), "a"),
                loopBack("c", "ACTION", map("to", "a", "while", "again", "maxIterations", 3), "b")));
        assertThat(report.errors()).isEmpty();
        assertThat(report.isValid()).isTrue();
    }

    @Test
    void forEachShapeErrors() {
        var report = LoopRules.analyze(flow(
                forEach("a", "TIMER", map("mode", "random", "maxConcurrency", 0, "as", "1x", "indexAs", "x y",
                        "output", "bad-name", "outputElement", "x"), "start"),
                forEach("b", "ACTION", map("items", "l", "mode", "sequential", "maxConcurrency", 3, "as", "i",
                        "indexAs", "i", "outputElement", "x"), "a")));
        assertThat(report.errors())
                .anySatisfy(e -> assertThat(e).contains("TIMER step cannot run once per item"))
                .anySatisfy(e -> assertThat(e).contains("must define items"))
                .anySatisfy(e -> assertThat(e).contains("mode 'random'"))
                .anySatisfy(e -> assertThat(e).contains("maxConcurrency must be a whole number"))
                .anySatisfy(e -> assertThat(e).contains("as '1x'"))
                .anySatisfy(e -> assertThat(e).contains("indexAs 'x y'"))
                .anySatisfy(e -> assertThat(e).contains("output 'bad-name'"))
                .anySatisfy(e -> assertThat(e).contains("maxConcurrency is for parallel mode"))
                .anySatisfy(e -> assertThat(e).contains("they must differ"))
                .anySatisfy(e -> assertThat(e).contains("outputElement needs output"));
    }

    @Test
    void repeatShapeErrors() {
        var report = LoopRules.analyze(flow(
                repeat("a", "CHOICE", map("maxIterations", 0, "delay", -5), "start"),
                repeat("b", "ACTION", map("until", "x", "delay", "soon"), "a"),
                new LoopRules.Node("c", "ACTION", List.of("b"), null, map("items", "l"), map("until", "x", "maxIterations", 2), null)));
        assertThat(report.errors())
                .anySatisfy(e -> assertThat(e).contains("CHOICE step cannot be repeated"))
                .anySatisfy(e -> assertThat(e).contains("must define until"))
                .anySatisfy(e -> assertThat(e).contains("maxIterations must be a whole number of at least 1"))
                .anySatisfy(e -> assertThat(e).contains("delay cannot be negative"))
                .anySatisfy(e -> assertThat(e).contains("must define maxIterations"))
                .anySatisfy(e -> assertThat(e).contains("'soon' is not milliseconds or an ISO-8601 duration"))
                .anySatisfy(e -> assertThat(e).contains("declares both forEach and repeat"));
    }

    @Test
    void loopBackTargetErrors() {
        assertThat(LoopRules.analyze(flow(loopBack("c", "ACTION", map("maxIterations", 2), "b"))).errors())
                .anySatisfy(e -> assertThat(e).contains("must define to"));
        assertThat(LoopRules.analyze(flow(loopBack("c", "ACTION", map("to", "zz", "maxIterations", 2), "b"))).errors())
                .anySatisfy(e -> assertThat(e).contains("unknown step 'zz'"));
        assertThat(LoopRules.analyze(flow(loopBack("c", "ACTION", map("to", "c", "maxIterations", 2), "b"))).errors())
                .anySatisfy(e -> assertThat(e).contains("use repeat"));
        assertThat(LoopRules.analyze(flow(loopBack("b", "ACTION", map("to", "c", "maxIterations", 2), "a"))).errors())
                .anySatisfy(e -> assertThat(e).contains("not upstream"));
        assertThat(LoopRules.analyze(flow(loopBack("end", "END", map("to", "a", "maxIterations", 2), "c"))).errors())
                .anySatisfy(e -> assertThat(e).contains("END and cannot carry a loopBack"));
    }

    @Test
    void aSectionMayNotHoldAReply() {
        var report = LoopRules.analyze(flow(n("b", "REPLY", "a"), loopBack("c", "ACTION", map("to", "a", "maxIterations", 2), "b")));
        assertThat(report.errors()).anySatisfy(e -> assertThat(e).contains("REPLY step 'b' again"));
    }

    @Test
    void loopsMustNestNotCross() {
        var nested = LoopRules.analyze(flow(
                loopBack("b", "ACTION", map("to", "a", "maxIterations", 2), "a"),
                loopBack("c", "ACTION", map("to", "a", "maxIterations", 2), "b")));
        assertThat(nested.errors()).isEmpty();
        var crossing = LoopRules.analyze(flow(
                loopBack("b", "ACTION", map("to", "start", "maxIterations", 2), "a"),
                loopBack("c", "ACTION", map("to", "a", "maxIterations", 2), "b")));
        assertThat(crossing.errors()).anySatisfy(e -> assertThat(e).contains("cross"));
    }

    @Test
    void aCompensationCannotLoop() {
        var nodes = flow(new LoopRules.Node("a", "ACTION", List.of("start"), "undo", null, null, null),
                repeat("undo", "ACTION", map("until", "x", "maxIterations", 2)));
        assertThat(LoopRules.analyze(nodes).errors()).anySatisfy(e -> assertThat(e).contains("is a compensation"));
    }

    @Test
    void aRepeatInsideALoopIsWorthAWarning() {
        var report = LoopRules.analyze(flow(
                repeat("b", "ACTION", map("until", "x", "maxIterations", 2), "a"),
                loopBack("c", "ACTION", map("to", "a", "maxIterations", 3), "b")));
        assertThat(report.errors()).isEmpty();
        assertThat(report.warnings()).anySatisfy(w -> assertThat(w).contains("repeats inside the loop"));
    }

    @Test
    void theSectionIsEveryStepOnAPathBetweenTheTwo() {
        var nodes = List.of(n("s", "START"), n("f", "FORK", "s"), n("x", "ACTION", "f"), n("y", "ACTION", "f"),
                n("j", "JOIN", "x", "y"), n("side", "ACTION", "f"), n("l", "ACTION", "j"));
        var successors = LoopRules.successorsOf(nodes);
        assertThat(LoopRules.section("f", "l", successors, null)).containsExactlyInAnyOrder("f", "x", "y", "j", "l");
        assertThat(LoopRules.section("l", "f", successors, null)).isEmpty();
    }
}
