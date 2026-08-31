package io.mateu.workflow.infra.out.async;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the relay does when the broker stops answering.
 *
 * <p>The case this is written from: a pod polling at 50 ms, under load, with the broker down. Sends
 * fail per message inside the drain — they never throw out of it — so the rows stay Pending, the
 * next commit raises the signal, and the relay is back at the broker immediately. The retry rate
 * becomes the write rate.
 */
class RelayPaceTest {

    /** No jitter, so the sequence itself can be asserted; jitter has its own test below. */
    private static RelayPace pace() {
        return new RelayPace(100, 5_000, 0.0);
    }

    @Nested
    @DisplayName("what counts as a stall")
    class Classification {

        @Test
        @DisplayName("claimed work that settled nothing is a stall")
        void claimedAndSettledNothing() {
            assertThat(RelayPace.isStall(500, 0)).isTrue();
        }

        /**
         * The one that matters most. An empty outbox is the healthy, common case — if it counted
         * as a stall an idle engine would pace itself down to the cap and then take seconds to
         * relay the next message anyone wrote.
         */
        @Test
        @DisplayName("nothing to do is not a stall")
        void anEmptyOutboxIsNotAStall() {
            assertThat(RelayPace.isStall(0, 0)).isFalse();
        }

        /**
         * A poisoned row is parked as Error rather than retried, so it is settled-by-parking and
         * not a failure to deliver. One of them among fifty must not pace down the other
         * forty-nine.
         */
        @Test
        @DisplayName("settling some of what was claimed is progress")
        void partialProgressIsNotAStall() {
            assertThat(RelayPace.isStall(50, 49)).isFalse();
            assertThat(RelayPace.isStall(50, 1)).isFalse();
        }
    }

    @Nested
    @DisplayName("the wait between passes")
    class Waiting {

        @Test
        @DisplayName("doubles for each pass that settles nothing")
        void growsWhileTheBrokerRefuses() {
            var pace = pace();

            assertThat(pace.stalled().toMillis()).isEqualTo(100);
            assertThat(pace.stalled().toMillis()).isEqualTo(200);
            assertThat(pace.stalled().toMillis()).isEqualTo(400);
            assertThat(pace.stalled().toMillis()).isEqualTo(800);
        }

        /**
         * Nothing is relayed while the relay waits, so the cap is also how long the engine can stay
         * quiet after the broker comes back. Steps here are paced in tens of milliseconds.
         */
        @Test
        @DisplayName("stops growing at the cap")
        void neverWaitsLongerThanTheCap() {
            var pace = pace();
            for (int i = 0; i < 20; i++) {
                pace.stalled();
            }

            assertThat(pace.stalled().toMillis()).isEqualTo(5_000);
        }

        @Test
        @DisplayName("a pass that settles something puts it back to the start")
        void recoveryResetsTheWait() {
            var pace = pace();
            pace.stalled();
            pace.stalled();
            pace.stalled();

            pace.progressed();

            assertThat(pace.consecutiveStalls()).isZero();
            assertThat(pace.stalled().toMillis()).isEqualTo(100);
        }

        /**
         * An idle relay calls this on every pass. If it did anything but stay at zero, the first
         * stall after a quiet period would start somewhere up the curve.
         */
        @Test
        @DisplayName("progress on an already-healthy relay changes nothing")
        void repeatedProgressIsHarmless() {
            var pace = pace();
            for (int i = 0; i < 100; i++) {
                pace.progressed();
            }

            assertThat(pace.consecutiveStalls()).isZero();
            assertThat(pace.stalled().toMillis()).isEqualTo(100);
        }

        @Test
        @DisplayName("the stall count is what the log line reports")
        void countsConsecutiveStalls() {
            var pace = pace();

            assertThat(pace.consecutiveStalls()).isZero();
            pace.stalled();
            assertThat(pace.consecutiveStalls()).isEqualTo(1);
            pace.stalled();
            assertThat(pace.consecutiveStalls()).isEqualTo(2);
        }
    }

    /**
     * Every pod in a fleet stalls on the same broker outage at the same instant. Without jitter
     * they would all come back on the same tick, which is the thundering herd the outage is about
     * to be followed by.
     */
    @Test
    @DisplayName("jitter spreads the fleet's retries")
    void jitterSpreadsTheRetries() {
        var pace = new RelayPace(1_000, 5_000, 0.5);

        var waits = new java.util.HashSet<Long>();
        for (int i = 0; i < 50; i++) {
            waits.add(new RelayPace(1_000, 5_000, 0.5).stalled().toMillis());
        }

        assertThat(waits).hasSizeGreaterThan(1);
        assertThat(waits).allSatisfy(w -> assertThat(w).isBetween(500L, 1_500L));
        assertThat(pace.stalled().toMillis()).isBetween(500L, 1_500L);
    }
}
