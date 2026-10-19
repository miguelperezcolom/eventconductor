package io.mateu.workflow.application.ratelimit;

import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.infra.config.WorkerRateLimitProperties;
import io.mateu.workflow.infra.out.memory.InMemoryRateLimitStore;
import io.mateu.workflow.time.EngineClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class DispatchRateLimiterTest {

    static final Instant T0 = Instant.parse("2026-10-19T10:00:00Z");
    static final LocalDateTime L0 = LocalDateTime.ofInstant(T0, ZoneOffset.UTC);

    InMemoryRateLimitStore store;
    DispatchRateLimiter limiter;

    @BeforeEach
    void setUp() {
        EngineClock.set(Clock.fixed(T0, ZoneOffset.UTC));
        var properties = new WorkerRateLimitProperties();
        properties.getRateLimits().put("partner", new WorkerRateLimitProperties.Limit(2, Duration.ofMinutes(1)));
        properties.getRateLimits().put("broken", new WorkerRateLimitProperties.Limit(0, Duration.ofMinutes(1)));
        store = new InMemoryRateLimitStore();
        limiter = new DispatchRateLimiter(properties, store, mock(WorkflowMetrics.class));
    }

    @AfterEach
    void tearDown() {
        EngineClock.reset();
    }

    @Test
    void permitsAreTakenUntilTheWindowIsFullThenTasksWaitInLine() {
        assertThat(limiter.limits("partner")).isTrue();
        assertThat(limiter.limits("other")).isFalse();
        assertThat(limiter.limits("broken")).isFalse();
        assertThat(limiter.admitOrDefer("s1", "p1", "other", 50)).isTrue();
        assertThat(limiter.admitOrDefer("s1", "p1", "partner", 50)).isTrue();
        assertThat(limiter.admitOrDefer("s2", "p1", "partner", 50)).isTrue();
        assertThat(limiter.admitOrDefer("s3", "p1", "partner", 50)).isFalse();
        assertThat(store.deferredCount()).isEqualTo(1);
        assertThat(limiter.nextDueAt()).contains(L0.plusMinutes(1));

        var dispatched = new ArrayList<String>();
        assertThat(limiter.releaseDue(dispatched::add)).isZero();
        EngineClock.set(Clock.fixed(T0.plusSeconds(60), ZoneOffset.UTC));
        // A new task does not jump the one already waiting, even with a fresh window.
        assertThat(limiter.admitOrDefer("s4", "p2", "partner", 50)).isFalse();
        assertThat(limiter.releaseDue(dispatched::add)).isEqualTo(2);
        assertThat(dispatched).containsExactly("s3", "s4");
        assertThat(store.deferredCount()).isZero();
        assertThat(limiter.nextDueAt()).isEmpty();
    }

    @Test
    void aTaskThatFailsToDispatchGoesBackInLine() {
        limiter.admitOrDefer("s1", "p1", "partner", 50);
        limiter.admitOrDefer("s2", "p1", "partner", 50);
        limiter.admitOrDefer("s3", "p1", "partner", 50);
        EngineClock.set(Clock.fixed(T0.plusSeconds(60), ZoneOffset.UTC));
        assertThatThrownBy(() -> limiter.releaseDue(id -> {
            throw new IllegalStateException("broker down");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(store.deferred("partner", 10)).extracting(RateLimitStore.Deferred::stepExecutionId).containsExactly("s3");
    }

    @Test
    void priorityFirstThenOneTaskPerProcessInTurn() {
        var tasks = List.of(
                new RateLimitStore.Deferred("a1", "A", "t", 50, L0),
                new RateLimitStore.Deferred("a2", "A", "t", 50, L0.plusSeconds(1)),
                new RateLimitStore.Deferred("a3", "A", "t", 50, L0.plusSeconds(2)),
                new RateLimitStore.Deferred("b1", "B", "t", 50, L0.plusSeconds(3)),
                new RateLimitStore.Deferred("c1", "C", "t", 50, L0.plusSeconds(4)),
                new RateLimitStore.Deferred("u1", "U", "t", 90, L0.plusSeconds(9)),
                new RateLimitStore.Deferred("l1", "L", "t", 10, L0.minusSeconds(9)));
        assertThat(DispatchRateLimiter.fairOrder(tasks)).extracting(RateLimitStore.Deferred::stepExecutionId)
                .containsExactly("u1", "a1", "b1", "c1", "a2", "a3", "l1");
    }

    @Test
    void theMemoryStoreClaimsATaskOnce() {
        var task = new RateLimitStore.Deferred("x", "P", "t", 50, L0);
        store.defer(task);
        store.defer(task);
        assertThat(store.hasDeferred("t")).isTrue();
        assertThat(store.deferredTopics()).containsExactly("t");
        assertThat(store.claim("x")).isTrue();
        assertThat(store.claim("x")).isFalse();
        assertThat(store.windowEnd("t", Duration.ofSeconds(1))).isEmpty();
    }
}
