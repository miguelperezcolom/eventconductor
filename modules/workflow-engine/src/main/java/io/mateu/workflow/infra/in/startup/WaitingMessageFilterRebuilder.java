package io.mateu.workflow.infra.in.startup;

import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.services.messagerouting.WaitingMessageFilter;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Keeps the per-shard {@link WaitingMessageFilter} (layer 3 of sharded message routing) populated:
 * once at startup, then on a period.
 *
 * <p>The filter is add-only — a step starting to wait adds its pair, but a step that stops waiting
 * cannot be removed from a Bloom filter without risking a false negative. A periodic rebuild from the
 * authoritative set of waiting steps is what sheds those stale pairs, keeping the false-positive rate
 * from drifting up as processes come and go. The startup pass is also what populates the filter for
 * steps that were already waiting before this pod started (which emitted no subscribe event to it).
 *
 * <p><b>It must not hold up the boot, and must not need a database to reach it</b> (a pod may start
 * with PostgreSQL unavailable and pick up its work when the database returns — DIST-08), so it runs on
 * a daemon thread that retries the first pass until one gets through, then rebuilds on a fixed delay.
 * Until the first pass lands the filter answers "maybe" and every message runs the query, exactly as
 * before. Does nothing at all when the filter is disabled.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WaitingMessageFilterRebuilder implements ApplicationRunner {

    private static final long RETRY_DELAY_MS = 5_000;

    final StepExecutionRepository stepExecutionRepository;
    final WaitingMessageFilter waitingMessageFilter;

    private final long rebuildIntervalMs = 30_000;

    @Override
    public void run(ApplicationArguments args) {
        if (!waitingMessageFilter.isEnabled()) {
            return;
        }
        var thread = new Thread(this::rebuildForever, "waiting-message-filter-rebuild");
        thread.setDaemon(true);
        thread.start();
    }

    private void rebuildForever() {
        boolean populated = false;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                rebuildOnce();
                populated = true;
            } catch (Throwable e) {
                // Before the first pass this keeps the filter answering "maybe"; after it, the last
                // good filter stays in place until the next pass gets through.
                log.warn("Could not rebuild the waiting-message filter yet ({}) — retrying in {}ms",
                        e.getMessage(), populated ? rebuildIntervalMs : RETRY_DELAY_MS);
            }
            try {
                Thread.sleep(populated ? rebuildIntervalMs : RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** One rebuild from the current waiting steps. Package-private so a test can drive it directly. */
    void rebuildOnce() {
        var pairs = new ArrayList<String[]>();
        for (var stepExecution : stepExecutionRepository.findPendingOrRunning()) {
            var name = stepExecution.getAwaitingMessageName();
            var key = stepExecution.getAwaitingCorrelationKey();
            if (name != null && key != null) {
                pairs.add(new String[] {name, key});
            }
        }
        waitingMessageFilter.rebuild(pairs);
    }
}
