package io.mateu.workflow.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Runs the background imports a git webhook schedules one at a time, coalescing repeats.
 *
 * <p>Two imports of the same source running at once race: each prunes against the files it read,
 * so a stale import that finishes last prunes (archives, deletes) what a newer one just added. Run
 * through this, imports never overlap, and an import requested for a key that is already waiting is
 * not queued again — its latest request replaces the waiting one. A request that arrives while the
 * same key is <em>running</em> waits and runs once after it (the running one may have read the
 * source before the push), however many arrive meanwhile.
 *
 * <p>Off the request thread, on the given executor (the common pool by default, as
 * {@code CompletableFuture.runAsync} was), so a provider's short delivery timeout is never hit. A
 * failing import is logged and does not stop the ones after it.
 */
public final class CoalescingImportRunner {

    private static final Logger log = LoggerFactory.getLogger(CoalescingImportRunner.class);

    private final Executor executor;
    private final Object lock = new Object();
    private final LinkedHashMap<String, Runnable> waiting = new LinkedHashMap<>();
    private boolean draining;

    public CoalescingImportRunner() {
        this(ForkJoinPool.commonPool());
    }

    public CoalescingImportRunner(Executor executor) {
        this.executor = executor;
    }

    /** Schedules {@code work} for {@code key}, replacing a request for the same key that has not started yet. */
    public void submit(String key, Runnable work) {
        synchronized (lock) {
            waiting.put(key, work);
            if (draining) {
                return;
            }
            draining = true;
        }
        try {
            executor.execute(this::drain);
        } catch (RuntimeException e) {
            synchronized (lock) {
                draining = false;
            }
            throw e;
        }
    }

    private void drain() {
        while (true) {
            Runnable next;
            synchronized (lock) {
                var it = waiting.values().iterator();
                if (!it.hasNext()) {
                    draining = false;
                    return;
                }
                next = it.next();
                it.remove();
            }
            try {
                next.run();
            } catch (RuntimeException e) {
                log.error("Background import failed: {}", e.getMessage(), e);
            }
        }
    }
}
