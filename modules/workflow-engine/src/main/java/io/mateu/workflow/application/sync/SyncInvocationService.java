package io.mateu.workflow.application.sync;

import io.mateu.workflow.application.out.InvocationRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.UnknownWorkflowDefinitionException;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.out.WorkflowTracing;
import io.mateu.workflow.application.usecases.process.create.CreateProcessCommand;
import io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessReply;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.security.AuthorizationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Starts a process for a synchronous caller and waits — up to a deadline — for its reply.
 *
 * <p>Idempotent by {@code (definition, idempotency key)}: a retry with the same key lands on the
 * same process, and gets the reply it already gave if it gave one. The invocation record and the
 * process are created in one transaction (see {@link InvocationRepository#createWith}), so there is
 * never one without the other.
 *
 * <p>The process runs exactly as an asynchronously started one does — every transition persisted,
 * the reply recorded on the process. The wait is just a caller watching for that reply; when the
 * deadline passes first, the caller is answered "accepted, look here later" and nothing about the
 * process changes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SyncInvocationService {

    final WorkflowDefinitionRepository workflowDefinitionRepository;
    final InvocationRepository invocationRepository;
    final ProcessRepository processRepository;
    final CreateProcessUseCase createProcessUseCase;
    final SyncReplyWaiters waiters;
    final WorkflowMetrics workflowMetrics;
    final WorkflowTracing workflowTracing;

    /** Wait when neither the caller nor the definition says how long. */
    @org.springframework.beans.factory.annotation.Value("${workflow.sync.default-deadline-ms:5000}")
    long defaultDeadlineMs;

    /** The longest any caller may wait, whatever it asks for. */
    @org.springframework.beans.factory.annotation.Value("${workflow.sync.max-deadline-ms:30000}")
    long maxDeadlineMs;

    /** How long an invocation is remembered — and so how long a retry with its key finds it. */
    @org.springframework.beans.factory.annotation.Value("${workflow.sync.retention:PT24H}")
    Duration retention;

    /** What a caller asks for. {@code requestedWait} is null when it did not say. */
    public record StartRequest(String workflowDefinitionId, String idempotencyKey, String businessKey,
                               Map<String, String> variables, Duration requestedWait,
                               AuthorizationContext caller) {
    }

    /** The invocation a request resolved to, whether it created it, and how long to wait for it. */
    public record Started(Invocation invocation, boolean created, Duration timeToWait) {
    }

    /** What a caller is told: the reply if there is one, and where the process stands. */
    public record View(Invocation invocation, ProcessReply reply, ProcessStatus processStatus) {
        public boolean replied() {
            return reply != null;
        }
    }

    public Started start(StartRequest request) {
        var definition = workflowDefinitionRepository.findById(request.workflowDefinitionId())
                .orElseThrow(() -> new UnknownWorkflowDefinitionException(request.workflowDefinitionId()));
        if (!definition.isSyncInvocable()) {
            throw new SyncInvocationRejectedException(SyncInvocationRejectedException.Reason.NOT_SYNC_INVOCABLE,
                    "Workflow '" + definition.id() + "' cannot be invoked synchronously"
                            + " (it does not declare syncInvocation.enabled).");
        }
        if (!definition.status().accceptsNewInstances()) {
            throw new SyncInvocationRejectedException(SyncInvocationRejectedException.Reason.NOT_ACCEPTING,
                    "Workflow '" + definition.id() + "' is " + definition.status() + " and accepts no new instances.");
        }
        var wait = waitFor(definition, request.requestedWait());
        var hash = hashOf(request);

        var existing = invocationRepository.findByKey(definition.id(), request.idempotencyKey());
        if (existing.isPresent()) {
            return retried(existing.get(), hash, wait);
        }

        var now = LocalDateTime.now();
        var invocation = new Invocation(UUID.randomUUID().toString(), definition.id(), request.idempotencyKey(),
                hash, UUID.randomUUID().toString(), now.plus(wait), now, now.plus(retention),
                workflowTracing.currentTraceParent());
        try {
            invocationRepository.createWith(invocation, () -> create(invocation, request));
        } catch (InvocationRepository.DuplicateInvocationException e) {
            // Somebody else got the key between the lookup and the insert — a concurrent retry. Their
            // invocation is the one; this request joins it.
            var winner = invocationRepository.findByKey(definition.id(), request.idempotencyKey())
                    .orElseThrow(() -> e);
            return retried(winner, hash, wait);
        }
        workflowMetrics.syncInvocationStarted(definition.id());
        log.info("Synchronous invocation {} of '{}' started process {} (key '{}')",
                invocation.id(), definition.id(), invocation.processId(), request.idempotencyKey());
        return new Started(invocation, true, wait);
    }

    /** Waits for the invocation's reply, up to {@code wait}. Never fails: at worst it answers "not yet". */
    public CompletableFuture<View> await(Invocation invocation, Duration wait) {
        return waiters.await(invocation.processId(), wait)
                .thenApply(replied -> replied.map(process -> viewOf(invocation, process))
                        .orElseGet(() -> view(invocation)));
    }

    public Optional<Invocation> find(String invocationId) {
        return invocationRepository.findById(invocationId);
    }

    public Optional<Invocation> findByKey(String workflowDefinitionId, String idempotencyKey) {
        return invocationRepository.findByKey(workflowDefinitionId, idempotencyKey);
    }

    /** The invocation as it stands now. */
    public View view(Invocation invocation) {
        return processRepository.findById(invocation.processId())
                .map(process -> viewOf(invocation, process))
                .orElseGet(() -> new View(invocation, null, null));
    }

    /** The wait a caller gets: what it asked for, else the definition's default, else the engine's — capped. */
    public Duration waitFor(WorkflowDefinition definition, Duration requested) {
        long millis;
        if (requested != null) {
            millis = requested.toMillis();
        } else if (definition != null && definition.syncInvocation() != null
                && definition.syncInvocation().defaultDeadlineMs() > 0) {
            millis = definition.syncInvocation().defaultDeadlineMs();
        } else {
            millis = defaultDeadlineMs;
        }
        return Duration.ofMillis(Math.max(0, Math.min(millis, maxDeadlineMs)));
    }

    /** The capped wait for a caller that is only reading (GET), not starting. */
    public Duration waitFor(Duration requested) {
        return waitFor(null, requested == null ? Duration.ZERO : requested);
    }

    private Started retried(Invocation existing, String hash, Duration wait) {
        if (!existing.requestHash().equals(hash)) {
            throw new SyncInvocationRejectedException(SyncInvocationRejectedException.Reason.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency key '" + existing.idempotencyKey() + "' was already used for a different request.");
        }
        log.info("Synchronous invocation {} retried with key '{}'", existing.id(), existing.idempotencyKey());
        return new Started(existing, false, wait);
    }

    /** Creates the process, inside the invocation's transaction; refuses (rolling both back) if it was not. */
    private void create(Invocation invocation, StartRequest request) {
        var variables = request.variables() == null ? List.<Variable>of() : request.variables().entrySet().stream()
                .map(entry -> new Variable(entry.getKey(), entry.getValue()))
                .toList();
        var businessKey = request.businessKey() == null || request.businessKey().isBlank()
                ? null : request.businessKey();
        if (businessKey != null && processRepository.findByBusinessKey(businessKey).isPresent()) {
            throw new SyncInvocationRejectedException(SyncInvocationRejectedException.Reason.BUSINESS_KEY_TAKEN,
                    "A process with business key '" + businessKey + "' already exists.");
        }
        createProcessUseCase.handle(new CreateProcessCommand(invocation.processId(),
                invocation.workflowDefinitionId(), businessKey, variables, null, request.caller()));
        if (processRepository.findById(invocation.processId()).isEmpty()) {
            // CreateProcessUseCase declines silently (a definition switched off under us, a business
            // key taken by a concurrent start). The caller must hear about it, and the invocation
            // must not survive pointing at nothing.
            throw new SyncInvocationRejectedException(SyncInvocationRejectedException.Reason.NOT_ACCEPTING,
                    "The process was not created (the definition stopped accepting instances,"
                            + " or its business key was taken concurrently).");
        }
    }

    private View viewOf(Invocation invocation, Process process) {
        return new View(invocation, process.getReply(), process.getStatus());
    }

    /**
     * The request's fingerprint: business key and variables, in a canonical order. The idempotency
     * key is not part of it (it is the lookup), and neither is the wait (a retry may be patient
     * differently and is still the same request).
     */
    static String hashOf(StartRequest request) {
        var canonical = new StringBuilder();
        canonical.append("bk=").append(request.businessKey() == null ? "" : request.businessKey()).append('\n');
        new TreeMap<>(request.variables() == null ? Map.<String, String>of() : request.variables())
                .forEach((name, value) -> canonical.append(name).append('=').append(value).append('\n'));
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
