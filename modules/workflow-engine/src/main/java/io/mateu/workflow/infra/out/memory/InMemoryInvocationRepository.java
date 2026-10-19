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
 * Invocations in the heap, for memory mode. Creation is serialized <em>per idempotency key</em>:
 * the key check, the process creation (which in memory mode runs the process inline, as far as it
 * goes) and the record all happen under that key's lock, which is this mode's stand-in for the JPA
 * store's transaction and its unique index — a second request with the same key waits for the
 * first and then finds it, exactly as it would block on the index there.
 *
 * <p>It used to be one monitor for the whole repository, which made every synchronous invocation in
 * the JVM run one after another however many callers there were: measured with the benchmark suite,
 * 1, 4 and 16 concurrent callers all got the same ~520 requests/s, with one core busy. Requests with
 * different keys never needed each other's lock — the JPA store does not serialize them either.
 */
@Service
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryInvocationRepository implements InvocationRepository {

    private final Map<String, Invocation> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByKey = new ConcurrentHashMap<>();

    /**
     * Invocation id by the process it started. {@link #findByProcessId} runs on every REPLY step, and
     * it used to scan every invocation still retained ({@code workflow.sync.retention}, a day by
     * default), so each synchronous call got slower than the last: 560 → 170 requests/s for one
     * caller across three 45-second runs of the benchmark suite.
     */
    private final Map<String, String> idByProcessId = new ConcurrentHashMap<>();

    /** Lock striping by key: bounded memory, and two keys share a stripe only by hash collision. */
    private final Object[] stripes = new Object[256];

    {
        for (var i = 0; i < stripes.length; i++) {
            stripes[i] = new Object();
        }
    }

    @Override
    public Optional<Invocation> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Invocation> findByKey(String workflowDefinitionId, String idempotencyKey) {
        return Optional.ofNullable(idByKey.get(key(workflowDefinitionId, idempotencyKey))).map(byId::get);
    }

    @Override
    public Optional<Invocation> findByProcessId(String processId) {
        return Optional.ofNullable(idByProcessId.get(processId)).map(byId::get)
                .filter(invocation -> processId.equals(invocation.processId()));
    }

    @Override
    public void createWith(Invocation invocation, Runnable creation) {
        var key = key(invocation.workflowDefinitionId(), invocation.idempotencyKey());
        synchronized (stripes[Math.floorMod(key.hashCode(), stripes.length)]) {
            createUnderKeyLock(key, invocation, creation);
        }
    }

    private void createUnderKeyLock(String key, Invocation invocation, Runnable creation) {
        if (idByKey.containsKey(key)) {
            throw new DuplicateInvocationException("Idempotency key already used: " + invocation.idempotencyKey(), null);
        }
        // Recorded before the creation runs: in memory mode the process runs inline inside it, and
        // anything that looks the invocation up on the way (a reply, a view) must find it.
        byId.put(invocation.id(), invocation);
        idByKey.put(key, invocation.id());
        idByProcessId.put(invocation.processId(), invocation.id());
        try {
            creation.run();
        } catch (RuntimeException e) {
            byId.remove(invocation.id());
            idByKey.remove(key);
            idByProcessId.remove(invocation.processId(), invocation.id());
            throw e;
        }
    }

    /** Forgets the invocations that started these processes — the retention purge deleted them. */
    public synchronized int deleteByProcessIds(java.util.Collection<String> processIds) {
        var gone = byId.values().stream().filter(invocation -> processIds.contains(invocation.processId())).toList();
        gone.forEach(this::forget);
        return gone.size();
    }

    @Override
    public int purgeExpired(LocalDateTime now) {
        var expired = byId.values().stream().filter(invocation -> invocation.expiresAt().isBefore(now)).toList();
        expired.forEach(this::forget);
        return expired.size();
    }

    private void forget(Invocation invocation) {
        byId.remove(invocation.id());
        idByKey.remove(key(invocation.workflowDefinitionId(), invocation.idempotencyKey()));
        idByProcessId.remove(invocation.processId(), invocation.id());
    }

    private static String key(String workflowDefinitionId, String idempotencyKey) {
        return workflowDefinitionId + "\u0000" + idempotencyKey;
    }
}
