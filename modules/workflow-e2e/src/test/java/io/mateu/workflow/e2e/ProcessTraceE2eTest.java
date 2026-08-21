package io.mateu.workflow.e2e;

import io.mateu.workflow.application.services.ProcessTrace;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.RecordingTracing;
import io.mateu.workflow.e2e.support.RecordingTracing.Recorded;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TRACE-01..05 — a process comes out of the engine as one trace shaped like the process.
 *
 * <p>What this replaces is worth stating, because it is what the tests are written against. The
 * engine used to trace the code it was running: a span around a dispatch, a span around a relay
 * pass, a span around a step-over. Each of those lasts a millisecond or two and lives entirely
 * inside one method on one thread, and the context that would tie them together does not survive
 * the outbox row and the broker record between them — so what reached the backend was a scatter of
 * unrelated two-millisecond traces, one per hop, with nothing saying which process any of them
 * belonged to.
 *
 * <p>What is asserted here instead is the picture an operator actually wants: the process as the
 * root, its steps beneath it, each covering the time that step really took, so that reading the
 * waterfall top to bottom tells you what ran, in what order, and what ran at the same time as what.
 */
class ProcessTraceE2eTest extends AbstractE2eTest {

    @Autowired RecordingTracing tracing;
    @Autowired ProcessTrace processTrace;

    @BeforeEach
    void forgetEarlierSpans() {
        tracing.clear();
    }

    /** TRACE-01. Every span of a process is in the process's own trace, and there is only one. */
    @Test
    void oneProcessIsOneTrace() {
        succeedAll();
        createProcess("sequential-3", "trace-1");

        var expected = RecordingTracing.traceIdOf(processTrace.anchorFor(process("trace-1").getId()));
        assertThat(tracing.spans())
                .as("nothing about this process may be emitted outside its own trace")
                .isNotEmpty()
                .allSatisfy(span -> assertThat(span.traceId()).isEqualTo(expected));
    }

    /** TRACE-02. The root is the process; the steps hang off it, not off each other's dispatches. */
    @Test
    void theProcessIsTheRootAndItsStepsAreItsChildren() {
        succeedAll();
        createProcess("sequential-3", "trace-2");

        var recorded = recordedSpans();
        var root = spanNamed(recorded, "Sequential 3 steps");
        assertThat(root)
                .as("the process itself must be a span, named as the process is named")
                .isNotNull();
        assertThat(root.parentSpanId())
                .as("its parent is the derived anchor, which is never emitted — so it is the root")
                .isEqualTo(RecordingTracing.spanIdOf(processTrace.anchorFor(process("trace-2").getId())));

        for (var stepName : List.of("Step 1", "Step 2", "Step 3")) {
            var step = spanNamed(recorded, stepName);
            assertThat(step).as("a span for step '%s'", stepName).isNotNull();
            assertThat(step.parentSpanId())
                    .as("'%s' must hang off the process, not off whatever dispatched it", stepName)
                    .isEqualTo(root.spanId());
        }
    }

    /** TRACE-03. A span covers the work, not the call that emitted it. */
    @Test
    void aStepSpanCoversWhenTheStepRanRatherThanWhenItWasWrittenOut() {
        succeedAll();
        createProcess("sequential-3", "trace-3");

        var execution = step("trace-3", "s1");
        var span = spanNamed(recordedSpans(), "Step 1");

        assertThat(span.startedAt())
                .as("the span starts when the step started, to the second")
                .isEqualTo(execution.getStartedAt().atZone(java.time.ZoneId.systemDefault()).toInstant());
        assertThat(span.finishedAt())
                .isEqualTo(execution.getFinishedAt().atZone(java.time.ZoneId.systemDefault()).toInstant());
    }

    /** TRACE-04. Sequence reads as sequence: each step begins where the one before it left off. */
    @Test
    void stepsThatRanOneAfterAnotherDoNotOverlap() {
        succeedAll();
        createProcess("sequential-3", "trace-4");

        var recorded = recordedSpans();
        var one = spanNamed(recorded, "Step 1");
        var two = spanNamed(recorded, "Step 2");
        var three = spanNamed(recorded, "Step 3");

        assertThat(one.finishedAt()).isBeforeOrEqualTo(two.startedAt());
        assertThat(two.finishedAt()).isBeforeOrEqualTo(three.startedAt());
    }

    /**
     * TRACE-05. The branches of a fork are siblings under the process, not nested inside one
     * another. This is the shape the old spans could never produce: two branches dispatched by the
     * same fork appeared underneath whichever step-over happened to run them, which says nothing
     * about their relationship to each other.
     *
     * <p>Only the shape is asserted here, deliberately. This harness runs the embedded worker
     * synchronously and reentrantly on the calling thread, so branch A genuinely finishes before
     * branch B starts — there is no concurrency to observe, and asserting an overlap would be
     * asserting something that is not true of this run. That the spans overlap <em>when the steps
     * did</em> follows from their being built from the steps' own timestamps, which is pinned by
     * TRACE-03 above and, for the overlap itself, by {@code RecordProcessTraceServiceTest}.
     */
    @Test
    void theBranchesOfAForkAreSiblingsUnderTheProcess() {
        worker.on("a", TestWorker.succeed());
        worker.on("b", TestWorker.succeed());
        createProcess("parallel", "trace-5");

        var recorded = recordedSpans();
        var a = spanNamed(recorded, "A");
        var b = spanNamed(recorded, "B");
        var root = spanNamed(recorded, "Parallel fan-out (fork/join)");

        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        assertThat(a.parentSpanId()).isEqualTo(root.spanId());
        assertThat(b.parentSpanId())
                .as("both branches are children of the process, so they read as siblings")
                .isEqualTo(root.spanId());
    }

    /**
     * TRACE-06. The live spans — the dispatches and step-overs that used to be all there was — are
     * still emitted, and now land in the process's trace instead of starting one each. That is what
     * makes a process visible while it is still running, before its own span exists.
     */
    @Test
    void theLiveEngineSpansJoinTheProcessTraceInsteadOfStartingTheirOwn() {
        succeedAll();
        createProcess("sequential-3", "trace-6");

        var expected = RecordingTracing.traceIdOf(processTrace.anchorFor(process("trace-6").getId()));
        assertThat(tracing.spans().stream().filter(Recorded::live).toList())
                .as("the step-over and dispatch spans still exist")
                .isNotEmpty()
                .allSatisfy(span -> assertThat(span.traceId())
                        .as("…and belong to the process they were working on")
                        .isEqualTo(expected));
    }

    private void succeedAll() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());
    }

    /** The spans built from the durable record, as opposed to the live ones wrapped around code. */
    private List<Recorded> recordedSpans() {
        return tracing.spans().stream().filter(span -> !span.live()).toList();
    }

    private Recorded spanNamed(List<Recorded> spans, String name) {
        return spans.stream().filter(span -> name.equals(span.name())).findFirst().orElse(null);
    }
}
