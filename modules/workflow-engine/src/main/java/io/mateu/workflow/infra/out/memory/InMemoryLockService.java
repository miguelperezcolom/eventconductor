package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.LockService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-heap {@link LockService} for {@code memory} mode. A single JVM owns every process here, so a
 * global monitor is all the mutual exclusion the queue needs — there is no second writer to race,
 * which is the whole reason memory mode exists. The JDBC implementation is what earns its keep
 * across pods.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryLockService implements LockService {

    /**
     * A NUL can never appear in a {@code lockName} (a definition-declared identifier), so it is a
     * safe separator: no two distinct {@code (name, key)} pairs collide on the composite id, the
     * way a space would let {@code ("a b", "c")} and {@code ("a", "b c")} both become {@code "a b c"}.
     */
    private static final char SEP = '\u0000';

    @Value("${workflow.lock.lease-ms:900000}")
    long leaseMs;

    private record Holder(String processId, String stepExecutionId, LocalDateTime leaseDeadline) {}

    private record Waiter(String processId, String stepExecutionId) {}

    private final Map<String, Holder> held = new HashMap<>();
    private final Map<String, Deque<Waiter>> queues = new HashMap<>();

    private static String id(String lockName, String lockKey) {
        return lockName + SEP + lockKey;
    }

    private LocalDateTime newLease() {
        return LocalDateTime.now().plusNanos(leaseMs * 1_000_000);
    }

    @Override
    public synchronized Outcome acquire(String lockName, String lockKey, String processId, String stepExecutionId) {
        String id = id(lockName, lockKey);
        Holder current = held.get(id);
        if (current == null) {
            held.put(id, new Holder(processId, stepExecutionId, newLease()));
            return Outcome.ACQUIRED;
        }
        if (current.processId().equals(processId)) {
            return Outcome.ACQUIRED; // reentrant
        }
        Deque<Waiter> queue = queues.computeIfAbsent(id, k -> new ArrayDeque<>());
        boolean alreadyWaiting = queue.stream().anyMatch(w -> w.processId().equals(processId));
        if (!alreadyWaiting) {
            queue.addLast(new Waiter(processId, stepExecutionId));
        }
        return Outcome.ENQUEUED;
    }

    @Override
    public synchronized Optional<Grant> release(String lockName, String lockKey, String processId) {
        String id = id(lockName, lockKey);
        Holder current = held.get(id);
        if (current == null || !current.processId().equals(processId)) {
            return Optional.empty();
        }
        return reassignOrFree(id, lockName, lockKey);
    }

    @Override
    public synchronized List<Grant> releaseAll(String processId) {
        List<Grant> grants = new ArrayList<>();
        List<String> heldByMe = held.entrySet().stream()
                .filter(e -> e.getValue().processId().equals(processId))
                .map(Map.Entry::getKey)
                .toList();
        for (String id : heldByMe) {
            reassignOrFree(id, nameOf(id), keyOf(id)).ifPresent(grants::add);
        }
        queues.values().forEach(queue -> queue.removeIf(w -> w.processId().equals(processId)));
        queues.entrySet().removeIf(e -> e.getValue().isEmpty());
        return grants;
    }

    @Override
    public synchronized List<Grant> expireLeases(LocalDateTime now) {
        List<Grant> grants = new ArrayList<>();
        List<String> expired = held.entrySet().stream()
                .filter(e -> e.getValue().leaseDeadline() != null && e.getValue().leaseDeadline().isBefore(now))
                .map(Map.Entry::getKey)
                .toList();
        for (String id : expired) {
            reassignOrFree(id, nameOf(id), keyOf(id)).ifPresent(grants::add);
        }
        return grants;
    }

    /** Hand the lock to the earliest waiter (a {@link Grant}) or free it if none wait. */
    private Optional<Grant> reassignOrFree(String id, String lockName, String lockKey) {
        Deque<Waiter> queue = queues.get(id);
        if (queue != null && !queue.isEmpty()) {
            Waiter next = queue.pollFirst();
            if (queue.isEmpty()) {
                queues.remove(id);
            }
            held.put(id, new Holder(next.processId(), next.stepExecutionId(), newLease()));
            return Optional.of(new Grant(lockName, lockKey, next.processId(), next.stepExecutionId()));
        }
        held.remove(id);
        return Optional.empty();
    }

    private static String nameOf(String id) {
        return id.substring(0, id.indexOf(SEP));
    }

    private static String keyOf(String id) {
        return id.substring(id.indexOf(SEP) + 1);
    }
}
