package io.mateu.workflow.autoconfigure;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * A count that is fetched at most once per interval, however often it is read.
 *
 * <p>Micrometer calls a gauge's function on every scrape, and two of the engine's gauges answer
 * with {@code SELECT count(*)} against the database the whole engine is bottlenecked on: running
 * processes, and unsent outbox rows. Every pod, every scrape. Counting running processes walks one
 * index entry per running process, so the cost grows with exactly the number the gauge exists to
 * report, and the outbox count is cheapest when the outbox is drained and dearest when it is backed
 * up — which is when the database can least afford it. Observability that gets more expensive as
 * the system gets busier is a load amplifier wearing a dashboard.
 *
 * <p>So the value is cached. A gauge is a trend and an alerting signal, not a ledger: nobody acts
 * on the difference between "1,000,412 running" and the same number thirty seconds ago, and this
 * engine already treats gauges that way — the stalled-steps gauge reports whatever the scheduler's
 * last pass observed. What changes is that the cost stops scaling with the number of scrapers.
 *
 * <p>Stale rather than absent on failure: if the query throws, the previous value is kept and the
 * exception is swallowed. A database hiccup should not make a dashboard read zero, which is
 * indistinguishable from "everything finished" on precisely the two metrics people alert on.
 */
class CachedCount {

    private final LongSupplier count;
    private final long ttlNanos;
    private final LongSupplier nanoTime;

    private final AtomicReference<Sample> sample = new AtomicReference<>();

    private record Sample(double value, long takenAt) {}

    CachedCount(LongSupplier count, Duration ttl) {
        this(count, ttl, System::nanoTime);
    }

    /** Package-private for the test, which cannot wait thirty seconds to prove a TTL. */
    CachedCount(LongSupplier count, Duration ttl, LongSupplier nanoTime) {
        this.count = count;
        this.ttlNanos = ttl.toNanos();
        this.nanoTime = nanoTime;
    }

    double value() {
        var now = nanoTime.getAsLong();
        var current = sample.get();
        if (current != null && now - current.takenAt() < ttlNanos) {
            return current.value();
        }
        try {
            var fresh = new Sample(count.getAsLong(), now);
            sample.set(fresh);
            return fresh.value();
        } catch (Exception e) {
            // Deliberately quiet and deliberately stale. Logging per scrape would turn a blip into
            // a log flood, and the meter that matters — the engine's own error counters — is not
            // this one.
            return current != null ? current.value() : Double.NaN;
        }
    }
}
