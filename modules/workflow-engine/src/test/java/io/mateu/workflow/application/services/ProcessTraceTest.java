package io.mateu.workflow.application.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** TRACE-12..19 — the derived anchor, which is the only thing tying a process's spans together. */
class ProcessTraceTest {

    /** Everything traced, so the anchor's flag is never what decides an assertion about ids. */
    private final ProcessTrace alwaysOn = new ProcessTrace(1.0);

    private static String traceIdOf(String anchor) {
        return anchor.split("-")[1];
    }

    private static boolean isSampled(String anchor) {
        return "01".equals(anchor.split("-")[3]);
    }

    /**
     * TRACE-12. The property everything else rests on. No pod ever tells another pod which trace a
     * process is in — it is computed, so a step-over on one node and a dispatch on another arrive
     * at the same answer with nothing propagated and nothing stored.
     */
    @Test
    void theSameProcessAlwaysYieldsTheSameAnchor() {
        var once = alwaysOn.anchorFor("f2a1c0de-0000-4000-8000-000000000001");
        var again = alwaysOn.anchorFor("f2a1c0de-0000-4000-8000-000000000001");

        assertThat(once).isEqualTo(again);
    }

    /** TRACE-13. And two processes are never in the same trace. */
    @Test
    void differentProcessesYieldDifferentAnchors() {
        assertThat(alwaysOn.anchorFor("p-1")).isNotEqualTo(alwaysOn.anchorFor("p-2"));
    }

    /**
     * TRACE-14. A W3C traceparent, to the letter: version 00, a 32-hex-character trace id, a
     * 16-hex-character span id, and a two-digit flag. A backend rejects anything else outright, and
     * it would do so silently — the spans simply would not appear.
     */
    @Test
    void theAnchorIsAWellFormedTraceparent() {
        assertThat(alwaysOn.anchorFor("p-1")).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
    }

    /** TRACE-15. No process id, no trace; the adapters read null as "do not emit". */
    @Test
    void thereIsNoAnchorWithoutAProcess() {
        assertThat(alwaysOn.anchorFor(null)).isNull();
        assertThat(alwaysOn.anchorFor("  ")).isNull();
    }

    // ---------------------------------------------------------------- sampling

    /**
     * TRACE-16. The configured probability governs process traces too.
     *
     * <p>It did not, and the way it failed is the kind that does not show up as an error anywhere.
     * Spring Boot's sampler is {@code ParentBased(TraceIdRatioBased(p))}, which honours a remote
     * parent's decision and only consults the ratio when there is no parent — so an anchor that
     * always claimed to be sampled exported every process trace whatever the property said. The
     * property still governed the auto-instrumented HTTP and JDBC traces, so it looked as though it
     * was working.
     */
    @Test
    void nothingIsTracedAtAProbabilityOfZero() {
        var off = new ProcessTrace(0.0);

        for (int i = 0; i < 100; i++) {
            assertThat(isSampled(off.anchorFor("p-" + i))).isFalse();
        }
    }

    /** …and everything is at 1. */
    @Test
    void everythingIsTracedAtAProbabilityOfOne() {
        for (int i = 0; i < 100; i++) {
            assertThat(isSampled(alwaysOn.anchorFor("p-" + i))).isTrue();
        }
    }

    /**
     * TRACE-17. In between, the rate is the rate. The trace id is a hash, so its low bytes — the
     * half the SDK's own sampler treats as random — are uniform, and the proportion comes out as
     * configured. A wide band, because this is asserting that the arithmetic is right rather than
     * that a sample of ten thousand hit an exact figure.
     */
    @Test
    void theRateIsRoughlyWhatWasAskedFor() {
        for (var probability : new double[]{0.1, 0.25, 0.5, 0.9}) {
            var trace = new ProcessTrace(probability);
            int sampled = 0;
            int total = 10_000;
            for (int i = 0; i < total; i++) {
                if (isSampled(trace.anchorFor("process-" + i))) {
                    sampled++;
                }
            }
            assertThat((double) sampled / total)
                    .as("sampling probability %s", probability)
                    .isCloseTo(probability, org.assertj.core.data.Offset.offset(0.03));
        }
    }

    /**
     * TRACE-18. All or nothing per process, which matters more than the rate does.
     *
     * <p>The decision comes from the trace id, so every pod that touches the process — over its
     * whole life, across restarts and redeliveries — reaches the same one. The alternative, each
     * span rolling its own dice, would at 10% give a tenth of the dispatches of a tenth of the
     * processes: scattered fragments, and never a whole process to read.
     */
    @Test
    void theDecisionIsStableForAProcess() {
        var trace = new ProcessTrace(0.5);

        for (int i = 0; i < 200; i++) {
            var first = trace.anchorFor("p-" + i);
            assertThat(trace.anchorFor("p-" + i)).isEqualTo(first);
            assertThat(trace.sampled(traceIdOf(first))).isEqualTo(isSampled(first));
        }
    }

    /**
     * TRACE-19. Sampling changes whether a process is exported, never which trace it is in. Were
     * the trace id itself to move with the setting, turning sampling up would scatter a running
     * process across two traces.
     */
    @Test
    void theTraceIdDoesNotDependOnTheSamplingProbability() {
        var traceId = traceIdOf(new ProcessTrace(1.0).anchorFor("p-1"));

        for (var probability : new double[]{0.0, 0.01, 0.5, 1.0}) {
            assertThat(traceIdOf(new ProcessTrace(probability).anchorFor("p-1"))).isEqualTo(traceId);
        }
    }
}
