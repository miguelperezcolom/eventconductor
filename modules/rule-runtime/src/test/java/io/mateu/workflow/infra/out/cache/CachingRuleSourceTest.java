package io.mateu.workflow.infra.out.cache;

import io.mateu.workflow.application.out.RuleSource;
import io.mateu.workflow.domain.Rule;
import io.mateu.workflow.domain.RuleType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CachingRuleSourceTest {

    static Rule rule(String id) {
        return new Rule(id, id, null, RuleType.EXPRESSION, 1, 0, null, null, List.of(),
                null, null, null, null);
    }

    static class CountingSource implements RuleSource {
        final List<Rule> rules = new ArrayList<>(List.of(rule("r1")));
        final AtomicInteger findAllCalls = new AtomicInteger();

        @Override
        public Optional<Rule> findById(String id) {
            return rules.stream().filter(r -> r.id().equals(id)).findFirst();
        }

        @Override
        public List<Rule> findAll() {
            findAllCalls.incrementAndGet();
            return List.copyOf(rules);
        }
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void loadsOnceWithinTtl() {
        var delegate = new CountingSource();
        var clock = new MutableClock();
        var cache = new CachingRuleSource(delegate, Duration.ofMinutes(5), clock);

        cache.findAll();
        cache.findById("r1");
        cache.findAll();

        assertThat(delegate.findAllCalls).hasValue(1);
    }

    @Test
    void reloadsAfterTtlExpires() {
        var delegate = new CountingSource();
        var clock = new MutableClock();
        var cache = new CachingRuleSource(delegate, Duration.ofMinutes(5), clock);

        cache.findAll();
        clock.now = clock.now.plus(Duration.ofMinutes(6));
        cache.findAll();

        assertThat(delegate.findAllCalls).hasValue(2);
    }

    @Test
    void zeroTtlNeverExpires() {
        var delegate = new CountingSource();
        var clock = new MutableClock();
        var cache = new CachingRuleSource(delegate, Duration.ZERO, clock);

        cache.findAll();
        clock.now = clock.now.plus(Duration.ofDays(365));
        cache.findAll();

        assertThat(delegate.findAllCalls).hasValue(1);
    }

    @Test
    void missGoesToDelegateAndIsCached() {
        var delegate = new CountingSource();
        var cache = new CachingRuleSource(delegate, Duration.ofMinutes(5), new MutableClock());
        cache.findAll();
        delegate.rules.add(rule("r2"));

        assertThat(cache.findById("r2")).isPresent();
        assertThat(cache.findAll()).hasSize(2);
    }

    @Test
    void putAndInvalidateUpdateTheCacheInPlace() {
        var delegate = new CountingSource();
        var cache = new CachingRuleSource(delegate, Duration.ZERO, new MutableClock());
        cache.findAll();

        cache.put(rule("pushed"));
        assertThat(cache.findById("pushed")).isPresent();

        cache.invalidate("r1");
        delegate.rules.clear();
        assertThat(cache.findById("r1")).isEmpty();
    }

    @Test
    void explicitRefreshReloads() {
        var delegate = new CountingSource();
        var cache = new CachingRuleSource(delegate, Duration.ZERO, new MutableClock());
        cache.findAll();
        delegate.rules.add(rule("r2"));

        cache.refresh();

        assertThat(cache.findAll()).hasSize(2);
    }
}
