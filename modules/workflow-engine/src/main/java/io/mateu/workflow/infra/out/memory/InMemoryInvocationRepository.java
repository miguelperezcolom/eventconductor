package io.mateu.workflow.infra.out.memory;

import io.mateu.workflow.application.out.InvocationRepository;
import io.mateu.workflow.application.sync.Invocation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Invocations in the heap, for memory mode. Creation is serialized: the key check, the process
 * creation (which in memory mode runs the process inline, as far as it goes) and the record all
 * happen under one monitor, which is this mode's stand-in for the JPA store's transaction.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryInvocationRepository implements InvocationRepository {

    private final Map<String, Invocation> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByKey = new ConcurrentHashMap<>();

    @Override
    public Optional<Invocation> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Invocation> findByKey(String workflowDefinitionId, String idempotencyKey) {
        return Optional.ofNullable(idByKey.get(key(workflowDefinitionId, idempotencyKey))).map(byId::get);
    }

    @Override
    public synchronized void createWith(Invocation invocation, Runnable creation) {
        var key = key(invocation.workflowDefinitionId(), invocation.idempotencyKey());
        if (idByKey.containsKey(key)) {
            throw new DuplicateInvocationException("Idempotency key already used: " + invocation.idempotencyKey(), null);
        }
        // Recorded before the creation runs: in memory mode the process runs inline inside it, and
        // anything that looks the invocation up on the way (a reply, a view) must find it.
        byId.put(invocation.id(), invocation);
        idByKey.put(key, invocation.id());
        try {
            creation.run();
        } catch (RuntimeException e) {
            byId.remove(invocation.id());
            idByKey.remove(key);
            throw e;
        }
    }

    @Override
    public int purgeExpired(LocalDateTime now) {
        var expired = byId.values().stream().filter(invocation -> invocation.expiresAt().isBefore(now)).toList();
        expired.forEach(invocation -> {
            byId.remove(invocation.id());
            idByKey.remove(key(invocation.workflowDefinitionId(), invocation.idempotencyKey()));
        });
        return expired.size();
    }

    private static String key(String workflowDefinitionId, String idempotencyKey) {
        return workflowDefinitionId + "\u0000" + idempotencyKey;
    }
}
