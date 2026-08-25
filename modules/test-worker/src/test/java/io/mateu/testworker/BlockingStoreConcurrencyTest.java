package io.mateu.testworker;

import io.mateu.testworker.application.ReceivedTaskStore;
import io.mateu.testworker.application.ScenarioResolver;
import io.mateu.testworker.application.SimulatedTaskHandler;
import io.mateu.testworker.application.TaskSimulator;
import io.mateu.testworker.domain.ReceivedTask;
import io.mateu.testworker.infra.out.persistence.InMemoryReceivedTaskStore;
import io.mateu.testworker.infra.out.persistence.InMemoryTaskOverrideStore;
import io.mateu.workflow.worker.CancelledTasks;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

import static io.mateu.testworker.Tasks.task;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the worker really runs tasks at the same time.
 *
 * <p>On paper it always did: the consumer uses {@code flatMap}, which is concurrent, and the
 * simulator uses {@code Mono.delay}, which does not block. Under {@code worker.persistence=jpa} it
 * did not — the store calls were blocking calls on the same small Reactor pool that is supposed to
 * be running every other task, so the concurrency collapsed to roughly one. Driving 5,000 processes
 * at a deployed engine gave 7.7 tasks/s at 200ms of simulated work each: about 1.5 tasks genuinely
 * in flight, with nothing saturated anywhere.
 *
 * <p>The in-memory map hid it, which is why the code could claim otherwise and be right about the
 * configuration it was tested in. This test puts the delay in the <em>store</em>, which is where JPA
 * puts it.
 *
 * <p>A timing assertion, with a margin chosen so it measures the difference rather than the machine:
 * serialised, the store work alone is 6 × 2 × 120ms = 1.4s; concurrent, it is about 240ms whatever
 * else is going on. The bar sits at 900ms, well clear of both.
 */
class BlockingStoreConcurrencyTest {

    private static final int TASKS = 6;
    private static final Duration STORE_CALL = Duration.ofMillis(120);
    private static final Duration SERIALISED_WOULD_EXCEED = Duration.ofMillis(900);

    /** A store that takes as long as a database round trip, which is what JPA is. */
    private static class SlowStore implements ReceivedTaskStore {
        private final InMemoryReceivedTaskStore delegate = new InMemoryReceivedTaskStore(100);
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        private static void asSlowAsADatabase() {
            try {
                Thread.sleep(STORE_CALL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public int previousDeliveriesOf(String taskExecutionId) {
            calls.incrementAndGet();
            asSlowAsADatabase();
            return delegate.previousDeliveriesOf(taskExecutionId);
        }

        @Override
        public String save(ReceivedTask task) {
            calls.incrementAndGet();
            asSlowAsADatabase();
            return delegate.save(task);
        }

        @Override
        public List<ReceivedTask> findAll() {
            return delegate.findAll();
        }

        @Override
        public java.util.Optional<ReceivedTask> findById(String id) {
            return delegate.findById(id);
        }

        @Override
        public void deleteAllById(List<String> ids) {
            delegate.deleteAllById(ids);
        }
    }

    @Test
    void a_slow_store_does_not_serialise_the_tasks_it_records() {
        var store = new SlowStore();
        var handler = new SimulatedTaskHandler(
                new ScenarioResolver(new InMemoryTaskOverrideStore(), Duration.ofMillis(20)),
                new TaskSimulator(),
                store);
        var broker = new RecordingBroker();
        var cancelled = new CancelledTasks();
        // One consumer thread, which is what a partition gives a pod. The whole question is whether
        // the store calls stay on it.
        var oneConsumerThread = Schedulers.fromExecutor(Executors.newSingleThreadExecutor());

        var started = System.nanoTime();
        Flux.range(0, TASKS)
                .flatMap(i -> handler.handle(broker, task("charge-card-" + i), cancelled))
                .subscribeOn(oneConsumerThread)
                .blockLast(Duration.ofSeconds(30));
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        // Every task read its delivery count and wrote its row, twice over: the tasks share a
        // taskExecutionId here, so the rows collapse to one — the store calls do not.
        assertThat(store.calls.get())
                .as("the store was actually exercised by all of them")
                .isGreaterThanOrEqualTo(TASKS * 2);
        assertThat(elapsed)
                .as("%d tasks with a %dms store call each: serialised they cannot finish in this",
                        TASKS, STORE_CALL.toMillis())
                .isLessThan(SERIALISED_WOULD_EXCEED);
    }
}
