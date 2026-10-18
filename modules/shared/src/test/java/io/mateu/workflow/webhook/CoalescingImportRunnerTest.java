package io.mateu.workflow.webhook;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CoalescingImportRunnerTest {

    @Test
    void importsNeverOverlapAndRequestsWhileOneRunsCoalesceIntoOneMoreRun() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            var runner = new CoalescingImportRunner(pool);
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var running = new AtomicInteger();
            var maxRunning = new AtomicInteger();
            var runs = Collections.synchronizedList(new ArrayList<String>());
            var done = new CountDownLatch(2);

            runner.submit("repo", () -> {
                track(running, maxRunning);
                started.countDown();
                await(release);
                runs.add("first");
                running.decrementAndGet();
                done.countDown();
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 5; i++) {
                var n = i;
                runner.submit("repo", () -> {
                    track(running, maxRunning);
                    runs.add("again-" + n);
                    running.decrementAndGet();
                    done.countDown();
                });
            }
            release.countDown();

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            // Once more after the running one, with the latest request — not five times.
            assertThat(runs).containsExactly("first", "again-4");
            assertThat(maxRunning.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aFailingImportDoesNotStopTheNextOne() throws Exception {
        var runner = new CoalescingImportRunner(Runnable::run);
        var ran = new ArrayList<String>();
        runner.submit("a", () -> {
            throw new IllegalStateException("boom");
        });
        runner.submit("b", () -> ran.add("b"));
        assertThat(ran).containsExactly("b");
    }

    private static void track(AtomicInteger running, AtomicInteger max) {
        max.accumulateAndGet(running.incrementAndGet(), Math::max);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
