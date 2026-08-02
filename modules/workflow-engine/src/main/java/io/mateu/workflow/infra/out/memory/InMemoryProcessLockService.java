package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.ProcessLockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory per-process lock for embedded/memory mode (single JVM). A striped map of reentrant
 * locks keeps two threads — the timeout scheduler and the event-processing thread, say — off the
 * same process at once.
 *
 * <p>Waits for the same bounded time as the JPA implementation so the two modes behave alike:
 * a caller that gets false means someone else has the process, in both.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
@Slf4j
public class InMemoryProcessLockService implements ProcessLockService {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Value("${workflow.process-lock-timeout-seconds:10}")
    int lockTimeoutSeconds;

    @Override
    public boolean runExclusively(String processId, Runnable action) {
        var lock = locks.computeIfAbsent(processId, key -> new ReentrantLock());
        try {
            if (!lock.tryLock(lockTimeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("Could not obtain exclusive access to process {} within {}s",
                        processId, lockTimeoutSeconds);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        try {
            action.run();
            return true;
        } finally {
            lock.unlock();
        }
    }
}
