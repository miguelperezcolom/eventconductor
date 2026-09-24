package io.mateu.workflow.analysis;

import io.mateu.workflow.analysis.ReplyPathAnalyzer.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplyPathAnalyzerTest {

    private static Node n(String id, String type, String... preconditions) {
        return new Node(id, type, List.of(preconditions), null, null);
    }

    private static Node timeoutRouting(String id, String type, String onTimeout, String... preconditions) {
        return new Node(id, type, List.of(preconditions), onTimeout, null);
    }

    private static Node compensable(String id, String compensation, String... preconditions) {
        return new Node(id, "ACTION", List.of(preconditions), null, compensation);
    }

    private static ReplyPathAnalyzer.Report analyze(boolean sync, Node... nodes) {
        return ReplyPathAnalyzer.analyze(List.of(nodes), sync);
    }

    @Test
    void aSingleReplyBeforeEndIsValid() {
        var report = analyze(true,
                n("start", "START"), n("work", "ACTION", "start"), n("reply", "REPLY", "work"),
                n("end", "END", "reply"));
        assertThat(report.errors()).isEmpty();
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    void anEarlyReplyWithWorkAfterItIsValid() {
        var report = analyze(true,
                n("start", "START"), n("reply", "REPLY", "start"), n("work", "ACTION", "reply"),
                n("end", "END", "work"));
        assertThat(report.isValid()).isTrue();
    }

    @Test
    void twoRepliesOnOnePathAreRejected() {
        var report = analyze(false,
                n("start", "START"), n("r1", "REPLY", "start"), n("r2", "REPLY", "r1"), n("end", "END", "r2"));
        assertThat(report.errors()).singleElement().asString().contains("same path");
    }

    @Test
    void oneReplyOnEachChoiceBranchIsValid() {
        var report = analyze(true,
                n("start", "START"), n("choice", "CHOICE", "start"),
                n("ok", "REPLY", "choice"), n("ko", "REPLY", "choice"),
                n("endOk", "END", "ok"), n("endKo", "END", "ko"));
        assertThat(report.errors()).isEmpty();
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    void repliesDeeperInsideDifferentChoiceBranchesAreValid() {
        var report = analyze(true,
                n("start", "START"), n("choice", "CHOICE", "start"),
                n("a1", "ACTION", "choice"), n("fork", "FORK", "a1"), n("a2", "ACTION", "fork"),
                n("ok", "REPLY", "fork"), n("b1", "ACTION", "choice"), n("ko", "REPLY", "b1"),
                n("join", "JOIN", "a2", "ok"), n("end1", "END", "join"), n("end2", "END", "ko"));
        assertThat(report.errors()).isEmpty();
    }

    @Test
    void repliesOnForkBranchesAreRejected() {
        var report = analyze(false,
                n("start", "START"), n("fork", "FORK", "start"),
                n("r1", "REPLY", "fork"), n("r2", "REPLY", "fork"), n("join", "JOIN", "r1", "r2"));
        assertThat(report.errors()).singleElement().asString().contains("can both run");
    }

    @Test
    void repliesAfterAnImplicitFanOutAreRejected() {
        // A plain step with two successors runs both of them — the same as a FORK.
        var report = analyze(false,
                n("start", "START"), n("a", "ACTION", "start"), n("r1", "REPLY", "a"), n("r2", "REPLY", "a"));
        assertThat(report.errors()).hasSize(1);
    }

    @Test
    void aReplyAfterAChoiceMergesWithAReplyOnOneBranchOnTheSamePath() {
        var report = analyze(false,
                n("start", "START"), n("choice", "CHOICE", "start"),
                n("early", "REPLY", "choice"), n("other", "ACTION", "choice"),
                n("join", "JOIN", "early", "other"), n("late", "REPLY", "join"));
        assertThat(report.errors()).singleElement().asString().contains("same path");
    }

    @Test
    void aChoiceThatDoesNotDominateBothRepliesDoesNotSeparateThem() {
        // r2 can also be reached through the FORK, bypassing the CHOICE.
        var report = analyze(false,
                n("start", "START"), n("fork", "FORK", "start"),
                n("choice", "CHOICE", "fork"), n("r1", "REPLY", "choice"), n("x", "ACTION", "choice"),
                n("y", "ACTION", "fork"), n("merge", "JOIN", "x", "y"), n("r2", "REPLY", "merge"));
        assertThat(report.errors()).hasSize(1);
    }

    @Test
    void theNormalAndTheTimeoutRouteOfAStepAreExclusive() {
        var report = analyze(true,
                n("start", "START"), timeoutRouting("call", "ACTION", "late", "start"),
                n("onTime", "REPLY", "call"), n("late", "REPLY"),
                n("end1", "END", "onTime"), n("end2", "END", "late"));
        assertThat(report.errors()).isEmpty();
    }

    @Test
    void aReplyBeforeATimeoutRouteAndOneOnItAreOnTheSamePath() {
        var report = analyze(false,
                n("start", "START"), n("r1", "REPLY", "start"), timeoutRouting("call", "ACTION", "late", "r1"),
                n("late", "REPLY"));
        assertThat(report.errors()).hasSize(1);
    }

    @Test
    void aSyncInvocableDefinitionMustHaveAReachableReply() {
        var report = analyze(true, n("start", "START"), n("end", "END", "start"));
        assertThat(report.errors()).singleElement().asString().contains("no REPLY step");
    }

    @Test
    void aNonSyncDefinitionNeedsNoReply() {
        assertThat(analyze(false, n("start", "START"), n("end", "END", "start")).isValid()).isTrue();
    }

    @Test
    void anEndThatCanBeReachedWithoutAReplyIsAWarning() {
        var report = analyze(true,
                n("start", "START"), n("choice", "CHOICE", "start"),
                n("ok", "REPLY", "choice"), n("endOk", "END", "ok"), n("endSilent", "END", "choice"));
        assertThat(report.errors()).isEmpty();
        assertThat(report.warnings()).singleElement().asString().contains("endSilent");
    }

    @Test
    void aReplyUsedAsACompensationIsRejected() {
        var report = analyze(false,
                n("start", "START"), compensable("book", "undo", "start"), n("undo", "REPLY"),
                n("end", "END", "book"));
        assertThat(report.errors()).singleElement().asString().contains("compensation");
    }

    @Test
    void manyDisjointChoiceBranchesScale() {
        var nodes = new ArrayList<Node>();
        nodes.add(n("start", "START"));
        nodes.add(n("choice", "CHOICE", "start"));
        for (int i = 0; i < 60; i++) {
            nodes.add(n("a" + i, "ACTION", "choice"));
            nodes.add(n("r" + i, "REPLY", "a" + i));
            nodes.add(n("e" + i, "END", "r" + i));
        }
        assertThat(ReplyPathAnalyzer.analyze(nodes, true).isValid()).isTrue();
    }
}
