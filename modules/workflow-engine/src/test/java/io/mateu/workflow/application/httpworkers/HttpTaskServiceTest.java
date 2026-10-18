package io.mateu.workflow.application.httpworkers;

import io.mateu.workflow.application.out.UpstreamEventPublisher;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.MessageType;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.StepsInjected;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import io.mateu.workflow.infra.out.memory.InMemoryHttpTaskQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The worker side of the queue: leases, replies turned into the engine's events, and every refusal. */
class HttpTaskServiceTest {

    MutableClock clock = new MutableClock(Instant.parse("2026-10-18T10:00:00Z"));
    InMemoryHttpTaskQueue queue = new InMemoryHttpTaskQueue();
    List<DomainEvent> published = new ArrayList<>();
    boolean engineDown;
    UpstreamEventPublisher upstream = event -> {
        if (engineDown) {
            throw new IllegalStateException("engine down");
        }
        published.add(event);
    };
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    HttpWorkerMetrics metrics = new HttpWorkerMetrics(() -> registry);
    HttpTaskSignal signal = new HttpTaskSignal();
    HttpTaskInbox inbox = new HttpTaskInbox(queue, signal, metrics, clock);
    HttpTaskService service = new HttpTaskService(queue, upstream, metrics, clock);

    private void dispatch(String id, int attempt) {
        inbox.dispatched(new TaskExecutionRequested(id, "p-" + id, "wd", "charge", "",
                List.of(new Variable("amount", "42")), attempt), "payments");
    }

    private HttpTask lease(String workerId) {
        return service.claim(workerId, List.of("payments"), 1, Duration.ofSeconds(60)).get(0);
    }

    private double counter(String name, String tag, String value) {
        var counter = registry.find("eventconductor.workers.http." + name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void completingSendsTheEngineACompletionWithTheAttemptAndRemovesTheTask() {
        dispatch("se-1", 3);
        var task = lease("w1");
        assertThat(task.attempt()).isEqualTo(3);

        var variables = new LinkedHashMap<String, Object>();
        variables.put("chargeId", "ch-1");
        variables.put("total", 42.5);
        variables.put("lines", List.of(Map.of("sku", "A")));
        variables.put("none", null);
        service.complete("se-1", 3, variables);

        assertThat(published).hasSize(1);
        var reply = (TaskStatusChanged) published.get(0);
        assertThat(reply.taskExecutionId()).isEqualTo("se-1");
        assertThat(reply.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(reply.processId()).isEqualTo("p-se-1");
        assertThat(reply.attempt()).isEqualTo(3);
        assertThat(reply.nonRetryable()).isFalse();
        assertThat(reply.variables()).containsExactly(new Variable("chargeId", "ch-1"), new Variable("total", "42.5"),
                new Variable("lines", "[{\"sku\":\"A\"}]"), new Variable("none", null));
        assertThat(queue.find("se-1")).isEmpty();
        assertThat(counter("replies", "outcome", "completed")).isEqualTo(1);
        assertThat(counter("enqueued", "topic", "payments")).isEqualTo(1);
        assertThat(counter("activated", "topic", "payments")).isEqualTo(1);
    }

    @Test
    void aReplyForAReplacedDispatchIsRefusedAndNeverReachesTheEngine() {
        dispatch("se-1", 1);
        lease("w1");
        dispatch("se-1", 2); // the step timed out and was retried: a new dispatch

        assertThatThrownBy(() -> service.complete("se-1", 1, Map.of("late", "yes")))
                .isInstanceOfSatisfying(HttpTaskException.class,
                        e -> assertThat(e.reason()).isEqualTo(HttpTaskException.Reason.STALE_ATTEMPT));
        assertThat(published).isEmpty();
        assertThat(queue.find("se-1").orElseThrow().attempt()).isEqualTo(2);
        assertThat(counter("replies", "outcome", "stale")).isEqualTo(1);
    }

    @Test
    void afterALeaseExpiresTheTaskIsRedeliveredAndTheFirstReplyWins() {
        dispatch("se-1", 1);
        var first = lease("w1");
        clock.advance(Duration.ofSeconds(61));
        var second = lease("w2");
        assertThat(second.deliveries()).isEqualTo(2);
        assertThat(counter("redelivered", "topic", "payments")).isEqualTo(1);

        // The slow first worker finishes after all: same dispatch, so its reply is taken.
        service.complete("se-1", first.attempt(), Map.of("by", "w1"));
        // The second worker's reply finds the task gone.
        assertThatThrownBy(() -> service.complete("se-1", second.attempt(), Map.of("by", "w2")))
                .isInstanceOfSatisfying(HttpTaskException.class,
                        e -> assertThat(e.reason()).isEqualTo(HttpTaskException.Reason.NOT_FOUND));
        assertThat(published).hasSize(1);
        // And its heartbeat says the task is gone too.
        assertThatThrownBy(() -> service.heartbeat("se-1", second.leaseToken(), Duration.ofSeconds(30)))
                .isInstanceOfSatisfying(HttpTaskException.class,
                        e -> assertThat(e.reason()).isEqualTo(HttpTaskException.Reason.NOT_FOUND));
        assertThat(counter("replies", "outcome", "gone")).isEqualTo(1);
    }

    @Test
    void aHeartbeatExtendsTheLeaseOnlyForItsHolder() {
        dispatch("se-1", 1);
        var first = lease("w1");
        clock.advance(Duration.ofSeconds(50));

        var beat = service.heartbeat("se-1", first.leaseToken(), Duration.ofSeconds(120));
        assertThat(beat.cancelled()).isFalse();
        assertThat(beat.leaseUntil()).isEqualTo(service.now().plusSeconds(120));

        clock.advance(Duration.ofSeconds(100)); // past the original lease, within the extension
        assertThat(service.claim("w2", List.of("payments"), 1, Duration.ofSeconds(60))).isEmpty();

        clock.advance(Duration.ofSeconds(30));
        var second = lease("w2");
        assertThatThrownBy(() -> service.heartbeat("se-1", first.leaseToken(), Duration.ofSeconds(60)))
                .isInstanceOfSatisfying(HttpTaskException.class,
                        e -> assertThat(e.reason()).isEqualTo(HttpTaskException.Reason.LEASE_LOST));
        assertThat(service.heartbeat("se-1", second.leaseToken(), Duration.ofSeconds(60)).cancelled()).isFalse();
        assertThatThrownBy(() -> service.heartbeat("se-1", " ", Duration.ofSeconds(60)))
                .isInstanceOfSatisfying(HttpTaskException.class,
                        e -> assertThat(e.reason()).isEqualTo(HttpTaskException.Reason.INVALID));
    }

    @Test
    void aCancellationReachesTheWorkerThroughItsHeartbeat() {
        dispatch("se-1", 1);
        var task = lease("w1");

        assertThat(inbox.cancelled("se-1")).isTrue();
        var beat = service.heartbeat("se-1", task.leaseToken(), Duration.ofSeconds(30));

        assertThat(beat.cancelled()).isTrue();
        assertThat(queue.find("se-1")).isEmpty();
        assertThat(counter("cancelled", "outcome", "flagged")).isEqualTo(1);
        assertThat(counter("cancelled", "outcome", "delivered")).isEqualTo(1);
    }

    @Test
    void aCancelledTaskNobodyHeldIsSimplyDropped() {
        dispatch("se-1", 1);

        assertThat(inbox.cancelled("se-1")).isTrue();
        assertThat(queue.find("se-1")).isEmpty();
        assertThat(inbox.cancelled("se-1")).isFalse();
        assertThat(counter("cancelled", "outcome", "removed")).isEqualTo(1);
    }

    @Test
    void aReplyToACancelledTaskIsRefused() {
        dispatch("se-1", 1);
        lease("w1");
        inbox.cancelled("se-1");

        assertThatThrownBy(() -> service.complete("se-1", 1, Map.of()))
                .isInstanceOfSatisfying(HttpTaskException.class,
                        e -> assertThat(e.reason()).isEqualTo(HttpTaskException.Reason.CANCELLED));
        assertThat(published).isEmpty();
        assertThat(queue.find("se-1")).isEmpty();
    }

    @Test
    void aFailureLogsItsReasonThenFailsTheStep() {
        dispatch("se-1", 1);
        lease("w1");

        service.fail("se-1", 1, "CARD_DECLINED", "insufficient funds", true);

        assertThat(published).hasSize(2);
        var log = (TaskLogEmitted) published.get(0);
        assertThat(log.messageType()).isEqualTo(MessageType.Error);
        assertThat(log.message()).isEqualTo("CARD_DECLINED: insufficient funds");
        var reply = (TaskStatusChanged) published.get(1);
        assertThat(reply.status()).isEqualTo(TaskStatus.ERROR);
        assertThat(reply.nonRetryable()).isFalse();
        assertThat(reply.attempt()).isEqualTo(1);
        assertThat(queue.find("se-1")).isEmpty();
    }

    @Test
    void aNonRetryableFailureIsMarkedSoTheEngineSpendsNoRetries() {
        dispatch("se-1", 1);
        lease("w1");

        service.fail("se-1", 1, "INVALID_CARD", null, false);

        assertThat(((TaskLogEmitted) published.get(0)).message()).isEqualTo("[non-retryable] INVALID_CARD");
        assertThat(((TaskStatusChanged) published.get(1)).nonRetryable()).isTrue();
    }

    @Test
    void reasonsReadWellWhateverTheWorkerSent() {
        assertThat(HttpTaskService.reasonOf("CODE", "CODE")).isEqualTo("CODE");
        assertThat(HttpTaskService.reasonOf(null, "boom")).isEqualTo("boom");
        assertThat(HttpTaskService.reasonOf(" ", " ")).isEqualTo("The worker reported a failure without a reason");
    }

    @Test
    void anAttemptIsRequiredOnceTheTaskHasOne() {
        dispatch("se-1", 1);
        lease("w1");

        assertThatThrownBy(() -> service.complete("se-1", null, Map.of()))
                .isInstanceOfSatisfying(HttpTaskException.class,
                        e -> assertThat(e.reason()).isEqualTo(HttpTaskException.Reason.INVALID));
    }

    @Test
    void aReplyTheEngineCannotTakeKeepsTheTaskForTheRetry() {
        dispatch("se-1", 1);
        lease("w1");
        engineDown = true;

        assertThatThrownBy(() -> service.complete("se-1", 1, Map.of()))
                .isInstanceOfSatisfying(HttpTaskException.class,
                        e -> assertThat(e.reason()).isEqualTo(HttpTaskException.Reason.UNAVAILABLE));
        assertThat(queue.find("se-1")).isPresent();

        engineDown = false;
        service.complete("se-1", 1, null);
        assertThat(((TaskStatusChanged) published.get(0)).variables()).isEmpty();
    }

    @Test
    void aDynamicStepInjectsAndThenCompletes() {
        dispatch("se-1", 1);
        lease("w1");

        service.inject("se-1", 1, "[{\"id\":\"extra\",\"type\":\"ACTION\"}]");
        assertThat(queue.find("se-1")).isPresent();
        service.complete("se-1", 1, Map.of());

        var injected = (StepsInjected) published.get(0);
        assertThat(injected.taskExecutionId()).isEqualTo("se-1");
        assertThat(injected.processId()).isEqualTo("p-se-1");
        assertThat(injected.stepsJson()).contains("extra");
        assertThat(published.get(1)).isInstanceOf(TaskStatusChanged.class);
        assertThatThrownBy(() -> service.inject("se-2", 1, " "))
                .isInstanceOfSatisfying(HttpTaskException.class,
                        e -> assertThat(e.reason()).isEqualTo(HttpTaskException.Reason.INVALID));
    }

    @Test
    void aDuplicateDispatchQueuesNothingNew() {
        dispatch("se-1", 1);
        dispatch("se-1", 1);
        assertThat(counter("enqueued", "topic", "payments")).isEqualTo(1);
    }

    @Test
    void releasedLeasesAreAvailableAgainAndNothingIsClaimedForNothing() {
        dispatch("se-1", 1);
        var task = lease("w1");
        service.release(List.of(task));
        assertThat(lease("w2").workerId()).isEqualTo("w2");
        assertThat(service.claim("w3", List.of(), 5, Duration.ofSeconds(5))).isEmpty();
        assertThat(service.claim("w3", null, 5, Duration.ofSeconds(5))).isEmpty();
        assertThat(service.claim("w3", List.of("payments"), 0, Duration.ofSeconds(5))).isEmpty();
    }

    @Test
    void aFailingReleaseIsOnlyLogged() {
        var failing = new HttpTaskService(new InMemoryHttpTaskQueue() {
            @Override
            public synchronized void release(String id, String leaseToken) {
                throw new IllegalStateException("db down");
            }
        }, upstream, HttpWorkerMetrics.none(), clock);
        failing.release(List.of(HttpTask.queued("x", "p", "wd", "s", "", "payments", List.of(), 1, service.now())));
    }
}
