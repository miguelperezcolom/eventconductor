package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.flowcontrol.FlowControlStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** Flow-control state in the heap: memory persistence, one JVM. A lock per key, as the database's row lock. */
@Component
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryFlowControlStore implements FlowControlStore {

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, KeyState> states = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    @Override
    public <T> T withKey(String keyId, String scope, String flowKey, Function<KeyTx, T> action) {
        var lock = locks.computeIfAbsent(keyId, k -> new ReentrantLock());
        lock.lock();
        try {
            states.computeIfAbsent(keyId, k -> new KeyState(keyId, scope, flowKey, null, 0, null, null, null));
            return action.apply(new KeyTx() {
                @Override
                public KeyState state() {
                    return states.get(keyId);
                }

                @Override
                public void saveState(KeyState state) {
                    states.put(keyId, state);
                }

                @Override
                public Optional<Pending> debounce() {
                    return pending.values().stream()
                            .filter(p -> keyId.equals(p.keyId()) && p.kind() == Kind.DEBOUNCE).findFirst();
                }

                @Override
                public List<Pending> queued() {
                    return pending.values().stream()
                            .filter(p -> keyId.equals(p.keyId()) && p.kind() == Kind.THROTTLE)
                            .sorted(Comparator.comparing(Pending::id))
                            .toList();
                }

                @Override
                public void savePending(Pending p) {
                    pending.put(p.id(), p);
                }

                @Override
                public void deletePending(String id) {
                    pending.remove(id);
                }
            });
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<String> dueKeys(LocalDateTime now, int max) {
        var keys = new ArrayList<String>();
        pending.values().stream()
                .filter(p -> !p.dueAt().isAfter(now))
                .sorted(Comparator.comparing(Pending::dueAt))
                .map(Pending::keyId)
                .distinct()
                .limit(max)
                .forEach(keys::add);
        return keys;
    }

    @Override
    public Optional<LocalDateTime> nextDueAt() {
        return pending.values().stream().map(Pending::dueAt).min(Comparator.naturalOrder());
    }

    @Override
    public long pending(Kind kind) {
        return pending.values().stream().filter(p -> p.kind() == kind).count();
    }
}
