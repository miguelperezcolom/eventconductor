package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.ProcessUpdateRepository;
import io.mateu.workflow.application.updates.ProcessUpdate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process updates in the heap, for memory mode. The key check and the insert happen under one monitor —
 * this mode's stand-in for the JPA store's unique constraint — and so does an update-with-start's
 * creation, its stand-in for a transaction.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryProcessUpdateRepository implements ProcessUpdateRepository {

    private final Map<String, ProcessUpdate> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByKey = new ConcurrentHashMap<>();

    @Override
    public Optional<ProcessUpdate> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<ProcessUpdate> findByKey(String processId, String idempotencyKey) {
        return Optional.ofNullable(idByKey.get(key(processId, idempotencyKey))).map(byId::get);
    }

    @Override
    public List<ProcessUpdate> findByProcessId(String processId) {
        return byId.values().stream()
                .filter(update -> processId.equals(update.processId()))
                .sorted(Comparator.comparing(ProcessUpdate::requestedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ProcessUpdate::completedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Override
    public List<ProcessUpdate> findFinishedAmong(Collection<String> ids) {
        return ids.stream().map(byId::get).filter(update -> update != null && update.isFinal()).toList();
    }

    @Override
    public synchronized void insert(ProcessUpdate update) {
        var key = key(update.processId(), update.idempotencyKey());
        if (idByKey.containsKey(key) || byId.containsKey(update.id())) {
            throw new DuplicateUpdateException("Idempotency key already used: " + update.idempotencyKey(), null);
        }
        byId.put(update.id(), update);
        idByKey.put(key, update.id());
    }

    @Override
    public void save(ProcessUpdate update) {
        byId.put(update.id(), update);
    }

    @Override
    public synchronized void inTransaction(Runnable work) {
        work.run();
    }

    @Override
    public synchronized int expireKeys(LocalDateTime now) {
        var expired = byId.values().stream()
                .filter(update -> update.expiresAt() != null && update.expiresAt().isBefore(now))
                .toList();
        expired.forEach(update -> {
            idByKey.remove(key(update.processId(), update.idempotencyKey()));
            var freed = new ProcessUpdate(update.id(), update.processId(), update.workflowDefinitionId(), update.name(),
                    update.id(), update.requestHash(), update.payload(), update.status(), update.result(), update.error(),
                    update.requestedBy(), update.withStart(), update.requestedAt(), update.completedAt(), null);
            byId.put(update.id(), freed);
            idByKey.put(key(update.processId(), update.id()), update.id());
        });
        return expired.size();
    }

    @Override
    public synchronized int deleteByProcessIds(Collection<String> processIds) {
        var gone = byId.values().stream().filter(update -> processIds.contains(update.processId())).toList();
        gone.forEach(update -> {
            byId.remove(update.id());
            idByKey.remove(key(update.processId(), update.idempotencyKey()));
        });
        return gone.size();
    }

    private static String key(String processId, String idempotencyKey) {
        return processId + "\u0000" + idempotencyKey;
    }
}
