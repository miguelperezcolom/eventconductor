package io.mateu.workflow.autoconfigure;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The point of this class is what it does <em>not</em> do: query the database once per scrape, per
 * pod, for a number nobody reads to that precision.
 */
class CachedCountTest {

    private final AtomicLong clock = new AtomicLong();
    private final AtomicLong queries = new AtomicLong();

    private CachedCount counting(long value, Duration ttl) {
        return new CachedCount(() -> {
            queries.incrementAndGet();
            return value;
        }, ttl, clock::get);
    }

    @Test
    void queriesOnceHoweverManyTimesItIsScrapedWithinTheWindow() {
        var count = counting(42, Duration.ofSeconds(30));

        for (var scrape = 0; scrape < 100; scrape++) {
            assertThat(count.value()).isEqualTo(42);
        }

        assertThat(queries).hasValue(1);
    }

    @Test
    void queriesAgainOnceTheWindowHasPassed() {
        var count = counting(42, Duration.ofSeconds(30));

        count.value();
        clock.set(Duration.ofSeconds(31).toNanos());
        count.value();

        assertThat(queries).hasValue(2);
    }

    @Test
    void aFailingQueryKeepsTheLastValueRatherThanReportingZero() {
        // Reporting zero for "running processes" or "outbox pending" during a database hiccup is
        // indistinguishable from "everything finished", on the two metrics people alert on.
        var failing = new AtomicLong();
        var count = new CachedCount(() -> {
            if (failing.get() > 0) {
                throw new IllegalStateException("the database is having a moment");
            }
            return 7;
        }, Duration.ofSeconds(30), clock::get);

        assertThat(count.value()).isEqualTo(7);
        failing.set(1);
        clock.set(Duration.ofSeconds(31).toNanos());

        assertThat(count.value()).isEqualTo(7);
    }

    @Test
    void aFailureBeforeAnyValueExistsIsNotAFalseZero() {
        var count = new CachedCount(() -> {
            throw new IllegalStateException("down since boot");
        }, Duration.ofSeconds(30), clock::get);

        assertThat(count.value()).isNaN();
    }
}
