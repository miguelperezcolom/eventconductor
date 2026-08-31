package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.application.services.BackoffPolicy;

import java.time.Duration;

/**
 * How long the outbox relay waits before its next pass, once passes stop settling anything.
 *
 * <p>The relay's normal pace is not a sleep: it waits on {@link OutboxSignal}, which every pod
 * raises after committing a write of its own, so a message is relayed as soon as it exists. That is
 * the right pace while the broker is answering and the wrong one while it is not — a refused send
 * leaves the row Pending, the next commit raises the signal again, and the relay retries
 * immediately. The cadence of retries against a broker that is down becomes the cadence of writes,
 * not any configured interval. On a deployment polling at 50 ms under load that is a hot loop, a
 * connection attempt and an error line per message per pass.
 *
 * <p>So a pass that claims work and settles none of it is paced by this instead, and the signal is
 * deliberately ignored while it waits: honouring it is exactly what defeats the backoff.
 *
 * <p><b>What counts as a stall.</b> Claiming rows and settling none of them — see
 * {@link #isStall(int, int)}. Not "nothing to do": an empty outbox is the healthy case and must
 * keep waiting on the signal, or an idle engine would slow itself down to the cap. Not "settled
 * fewer than claimed" either: one poisoned row among fifty is parked as Error, not retried, and the
 * other forty-nine went.
 *
 * <p><b>Why the cap is seconds and not a minute.</b> Nothing is relayed while this waits, so the
 * cap is also how long the engine can stay quiet after the broker comes back. Steps are paced in
 * tens of milliseconds here; a minute of silence to save a few passes is the wrong trade.
 *
 * <p>The arithmetic is {@link BackoffPolicy}'s, built with this component's own settings rather
 * than the step-retry ones: the two answer different questions (how long to wait before asking a
 * worker again, versus before asking the broker again) and would drift if a single knob moved both.
 * Jitter is not decoration — every pod in the fleet stalls on the same broker outage at the same
 * instant, and without it they would all come back on the same tick.
 */
class RelayPace {

    private final BackoffPolicy policy;
    private int consecutiveStalls;

    RelayPace(long baseMs, long maxMs, double jitter) {
        this.policy = new BackoffPolicy(baseMs, 2.0, maxMs, jitter);
    }

    /**
     * A pass that claimed work and settled none of it. Delivery failures are caught per message
     * inside the drain, so a refusing broker does not throw — it comes back as this.
     */
    static boolean isStall(int claimed, int settled) {
        return claimed > 0 && settled == 0;
    }

    /** Records a stalled pass and returns how long to wait before the next one. */
    Duration stalled() {
        return policy.nextDelay(++consecutiveStalls);
    }

    /** Records a pass that settled something, or that had nothing to settle. */
    void progressed() {
        consecutiveStalls = 0;
    }

    /** How many passes in a row have settled nothing. Zero when the relay is healthy. */
    int consecutiveStalls() {
        return consecutiveStalls;
    }
}
