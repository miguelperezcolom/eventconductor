package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.httpworkers.HttpTask;
import io.mateu.workflow.application.httpworkers.HttpTaskService;
import io.mateu.workflow.application.httpworkers.HttpTaskSignal;
import io.mateu.workflow.application.httpworkers.HttpWorkerMetrics;
import io.mateu.workflow.infra.out.memory.InMemoryHttpTaskQueue;
import io.mateu.workflow.time.EngineClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The HTTP worker queue's scheduler pass: purges, gauges, and the wake-up — on demand and in the background. */
class HttpTaskMaintenanceTest {

    final InMemoryHttpTaskQueue queue = new InMemoryHttpTaskQueue();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final HttpWorkerMetrics metrics = new HttpWorkerMetrics(() -> registry);
    final AtomicInteger raised = new AtomicInteger();
    final HttpTaskSignal signal = new HttpTaskSignal() {
        @Override
        public void raise() {
            raised.incrementAndGet();
        }
    };
    final HttpTaskService service = new HttpTaskService(queue, event -> { }, metrics, Clock.systemUTC());

    @AfterEach
    void resetClock() {
        EngineClock.reset();
    }

    private HttpTaskMaintenance maintenance(boolean background) {
        return new HttpTaskMaintenance(queue, service, signal, metrics, Duration.ofMillis(20), Duration.ofDays(7),
                background);
    }

    private void queue(String id, LocalDateTime createdAt) {
        queue.offer(HttpTask.queued(id, "p", "wd", "s", "", "payments", List.of(), 1, createdAt));
    }

    @Test
    void orphansArePurgedOnceBusinessTimePassesTheirRetention() {
        queue("old", EngineClock.now());
        queue("new", EngineClock.now().plusDays(6));
        var pass = maintenance(false);

        assertThat(pass.runOnce()).isZero();
        // The test kit's move: business time jumps ten days; leases (wall time) are untouched.
        EngineClock.set(Clock.fixed(Instant.now().plus(Duration.ofDays(10)), ZoneId.systemDefault()));

        assertThat(pass.runOnce()).isEqualTo(1);
        assertThat(queue.find("old")).isEmpty();
        assertThat(queue.find("new")).isPresent();
    }

    @Test
    void theGaugesAreRefreshedAndWaitersWokenWhenSomethingIsAvailable() {
        queue("a", EngineClock.now());
        queue("b", EngineClock.now());
        service.claim("w1", List.of("payments"), 1, Duration.ofSeconds(60));

        maintenance(false).runOnce();

        assertThat(registry.find("eventconductor.workers.http.queued").tag("state", "available").gauge().value())
                .isEqualTo(1);
        assertThat(registry.find("eventconductor.workers.http.queued").tag("state", "leased").gauge().value())
                .isEqualTo(1);
        assertThat(raised.get()).isEqualTo(1);
    }

    @Test
    void inTheBackgroundItRunsByItselfAndSurvivesAFailingPass() {
        var failing = new HttpTaskMaintenance(new InMemoryHttpTaskQueue() {
            @Override
            public synchronized int purge(LocalDateTime now, LocalDateTime orphansBefore) {
                throw new IllegalStateException("db down");
            }
        }, service, signal, metrics, Duration.ofMillis(20), Duration.ofDays(7), true);
        failing.start();
        failing.stop();

        queue("a", EngineClock.now());
        var pass = maintenance(true);
        pass.start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> raised.get() > 0);
        } finally {
            pass.stop();
        }
        var idle = maintenance(false);
        idle.start();
        idle.stop();
    }
}
