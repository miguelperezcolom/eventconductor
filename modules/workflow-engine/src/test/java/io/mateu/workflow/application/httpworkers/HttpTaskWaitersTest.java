package io.mateu.workflow.application.httpworkers;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.infra.out.memory.InMemoryHttpTaskQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** The long-poll: woken by a task at once, empty at its deadline, FIFO, bounded, and never spinning. */
class HttpTaskWaitersTest {

    final AtomicInteger claims = new AtomicInteger();
    final InMemoryHttpTaskQueue queue = new InMemoryHttpTaskQueue() {
        @Override
        public synchronized List<HttpTask> claim(Collection<String> topics, int max, String workerId,
                                                 LocalDateTime now, LocalDateTime leaseUntil) {
            claims.incrementAndGet();
            return super.claim(topics, max, workerId, now, leaseUntil);
        }
    };
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final HttpWorkerMetrics metrics = new HttpWorkerMetrics(() -> registry);
    final HttpTaskSignal signal = new HttpTaskSignal();
    final HttpTaskInbox inbox = new HttpTaskInbox(queue, signal, metrics, java.time.Clock.systemUTC());
    final HttpTaskService service = new HttpTaskService(queue, event -> { }, metrics, java.time.Clock.systemUTC());
    HttpTaskWaiters waiters;

    private HttpTaskWaiters start(int maxWaiting, Duration pollInterval) {
        waiters = new HttpTaskWaiters(service, queue, signal, metrics, new HttpTaskWaiters.Settings(
                maxWaiting, pollInterval, Duration.ofMillis(100), Duration.ofDays(7)));
        waiters.start();
        return waiters;
    }

    @AfterEach
    void stop() {
        if (waiters != null) {
            waiters.stop();
        }
    }

    private void dispatch(String id) {
        inbox.dispatched(new TaskExecutionRequested(id, "p-" + id, "wd", "charge", "",
                List.of(new Variable("amount", "1")), 1), "payments");
    }

    @Test
    void aWaitingWorkerIsHandedATaskTheMomentItIsQueued() throws Exception {
        start(10, Duration.ofSeconds(30)); // so slow a poll that only the wake-up can explain a fast answer

        var activation = waiters.activate("w1", Set.of("payments"), 5, Duration.ofSeconds(60), Duration.ofSeconds(20));
        Thread.sleep(100);
        assertThat(activation).isNotDone();

        var queuedAt = System.nanoTime();
        dispatch("se-1");
        var tasks = activation.get(5, TimeUnit.SECONDS);

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queuedAt)).isLessThan(2_000);
        assertThat(tasks).extracting(HttpTask::id).containsExactly("se-1");
        assertThat(tasks.get(0).workerId()).isEqualTo("w1");
    }

    @Test
    void anActivationThatFindsNothingAnswersEmptyAtItsDeadlineWithoutSpinning() throws Exception {
        start(10, Duration.ofMillis(200));

        var started = System.nanoTime();
        var tasks = waiters.activate("w1", Set.of("payments"), 5, Duration.ofSeconds(60), Duration.ofMillis(1_000))
                .get(5, TimeUnit.SECONDS);
        var waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(tasks).isEmpty();
        assertThat(waited).isBetween(900L, 3_000L);
        // About one look per poll interval (plus the arrival and the deadline), not thousands.
        assertThat(claims.get()).isBetween(2, 12);
    }

    @Test
    void idleWorkersOnOneTopicCostOneQueryPerPassNotOnePerWorker() throws Exception {
        start(50, Duration.ofMillis(200));
        var activations = new java.util.ArrayList<java.util.concurrent.CompletableFuture<List<HttpTask>>>();
        for (int i = 0; i < 20; i++) {
            activations.add(waiters.activate("w" + i, Set.of("payments"), 1, Duration.ofSeconds(60),
                    Duration.ofMillis(1_000)));
        }
        for (var activation : activations) {
            assertThat(activation.get(5, TimeUnit.SECONDS)).isEmpty();
        }
        assertThat(claims.get()).isLessThan(60);
    }

    @Test
    void theWorkerThatWaitedLongestIsServedFirst() throws Exception {
        start(10, Duration.ofSeconds(30));
        var first = waiters.activate("first", Set.of("payments"), 1, Duration.ofSeconds(60), Duration.ofSeconds(20));
        Thread.sleep(50);
        var second = waiters.activate("second", Set.of("payments"), 1, Duration.ofSeconds(60), Duration.ofSeconds(20));
        Thread.sleep(50);
        assertThat(waiters.waitingWorkers()).containsExactly("first", "second");

        dispatch("se-1");
        assertThat(first.get(5, TimeUnit.SECONDS)).extracting(HttpTask::id).containsExactly("se-1");
        assertThat(second).isNotDone();

        dispatch("se-2");
        assertThat(second.get(5, TimeUnit.SECONDS)).extracting(HttpTask::id).containsExactly("se-2");
    }

    @Test
    void pastMaxWaitingNewActivationsAreRefused() {
        start(2, Duration.ofSeconds(30));
        waiters.activate("w1", Set.of("payments"), 1, Duration.ofSeconds(60), Duration.ofSeconds(20));
        waiters.activate("w2", Set.of("payments"), 1, Duration.ofSeconds(60), Duration.ofSeconds(20));

        assertThatThrownBy(() -> waiters.activate("w3", Set.of("payments"), 1, Duration.ofSeconds(60),
                Duration.ofSeconds(20)))
                .isInstanceOfSatisfying(HttpTaskException.class,
                        e -> assertThat(e.reason()).isEqualTo(HttpTaskException.Reason.OVERLOADED));
        assertThat(registry.find("eventconductor.workers.http.rejected").counter().count()).isEqualTo(1);
        assertThat(registry.find("eventconductor.workers.http.waiting").gauge().value()).isEqualTo(2);
    }

    @Test
    void aSlotIsFreedWhenTheCallerGoesAway() throws Exception {
        start(1, Duration.ofMillis(50));
        var gone = waiters.activate("w1", Set.of("payments"), 1, Duration.ofSeconds(60), Duration.ofSeconds(20));
        gone.complete(List.of()); // what the HTTP layer does when the request ends early

        var next = waiters.activate("w2", Set.of("payments"), 1, Duration.ofSeconds(60), Duration.ofSeconds(20));
        dispatch("se-1");
        assertThat(next.get(5, TimeUnit.SECONDS)).hasSize(1);
        await().atMost(Duration.ofSeconds(5)).until(() -> waiters.waitingCount() == 0);
    }

    @Test
    void aWaitOfZeroAnswersAtOnceWithWhatThereIs() throws Exception {
        start(10, Duration.ofSeconds(30));
        dispatch("se-1");
        assertThat(waiters.activate("w1", Set.of("payments"), 5, Duration.ofSeconds(60), Duration.ZERO)
                .get(5, TimeUnit.SECONDS)).hasSize(1);
        assertThat(waiters.activate("w1", Set.of("payments"), 5, Duration.ofSeconds(60), Duration.ZERO)
                .get(5, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    void maintenancePurgesAndRefreshesTheGauges() throws Exception {
        start(10, Duration.ofMillis(50));
        dispatch("se-1");
        dispatch("se-2");
        service.claim("w1", List.of("payments"), 1, Duration.ofSeconds(60));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(registry.find("eventconductor.workers.http.queued").tag("state", "available").gauge().value())
                    .isEqualTo(1);
            assertThat(registry.find("eventconductor.workers.http.queued").tag("state", "leased").gauge().value())
                    .isEqualTo(1);
        });
    }

    @Test
    void stoppingAnswersEveryWaiterAndRefusesNewOnes() throws Exception {
        start(10, Duration.ofSeconds(30));
        var waiting = waiters.activate("w1", Set.of("payments"), 1, Duration.ofSeconds(60), Duration.ofSeconds(20));
        waiters.stop();
        assertThat(waiting.get(1, TimeUnit.SECONDS)).isEmpty();
        assertThatThrownBy(() -> waiters.activate("w1", Set.of("payments"), 1, Duration.ofSeconds(60),
                Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(HttpTaskException.class,
                        e -> assertThat(e.reason()).isEqualTo(HttpTaskException.Reason.UNAVAILABLE));
        waiters = null;
    }

    @Test
    void aFailingClaimIsLoggedAndTheWaiterStillAnswered() throws Exception {
        var broken = new InMemoryHttpTaskQueue() {
            @Override
            public synchronized List<HttpTask> claim(Collection<String> topics, int max, String workerId,
                                                     LocalDateTime now, LocalDateTime leaseUntil) {
                throw new IllegalStateException("db down");
            }

            @Override
            public synchronized int purge(LocalDateTime now, LocalDateTime orphansBefore) {
                throw new IllegalStateException("db down");
            }
        };
        var brokenService = new HttpTaskService(broken, event -> { }, metrics, java.time.Clock.systemUTC());
        waiters = new HttpTaskWaiters(brokenService, broken, signal, metrics, new HttpTaskWaiters.Settings(
                10, Duration.ofMillis(50), Duration.ofMillis(50), Duration.ofDays(7)));
        waiters.start();
        waiters.start(); // idempotent

        assertThat(waiters.activate("w1", Set.of("payments"), 1, Duration.ofSeconds(60), Duration.ofMillis(300))
                .get(5, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    void tasksForACallerThatIsAlreadyGoneAreGivenBack() throws Exception {
        start(10, Duration.ofSeconds(30));
        var activation = waiters.activate("w1", Set.of("payments"), 1, Duration.ofSeconds(60), Duration.ofSeconds(20));
        // The future is finished by someone else between the claim and the hand-over — simulate by
        // completing it while the queue holds the only task; the pass then has nobody to give it to.
        activation.complete(List.of());
        dispatch("se-1");
        Thread.sleep(200);
        assertThat(queue.find("se-1").orElseThrow().leasedAt(LocalDateTime.now(java.time.Clock.systemUTC()))).isFalse();
    }

    @Test
    void theWakeUpOfATaskQueuedInATransactionWaitsForTheCommit() {
        var raised = new AtomicInteger();
        var localInbox = new HttpTaskInbox(queue, new HttpTaskSignal() {
            @Override
            public void raise() {
                raised.incrementAndGet();
            }
        }, metrics, java.time.Clock.systemUTC());
        TransactionSynchronizationManager.initSynchronization();
        try {
            localInbox.dispatched(new TaskExecutionRequested("se-tx", "p", "wd", "s", "", List.of(), 1), "payments");
            assertThat(raised.get()).isZero();
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(raised.get()).isEqualTo(1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
