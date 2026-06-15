package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.ProcessLockService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory per-process lock for embedded/memory mode (single JVM).
 * Uses a striped ReentrantLock map to prevent concurrent access from
 * multiple threads (e.g. TimeoutScheduler vs the event-processing thread).
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryProcessLockService implements ProcessLockService {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public boolean tryLock(String processId) {
        return locks.computeIfAbsent(processId, k -> new ReentrantLock()).tryLock();
    }

    @Override
    public void unlock(String processId) {
        ReentrantLock lock = locks.get(processId);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
